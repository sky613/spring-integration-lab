package com.example.integrationhub.service;

import com.example.integrationhub.client.ErpClient;
import com.example.integrationhub.client.ErpOrderRequest;
import com.example.integrationhub.client.ErpOrderResponse;
import com.example.integrationhub.client.MesAvailabilityRequest;
import com.example.integrationhub.client.MesAvailabilityResponse;
import com.example.integrationhub.client.MesClient;
import com.example.integrationhub.client.RetryExecutor;
import com.example.integrationhub.domain.ExternalSystem;
import com.example.integrationhub.domain.IntegrationErrorType;
import com.example.integrationhub.domain.IntegrationHistory;
import com.example.integrationhub.domain.IntegrationStatus;
import com.example.integrationhub.domain.IntegrationTrigger;
import com.example.integrationhub.dto.IntegrationHistoryResponse;
import com.example.integrationhub.dto.OrderResponse;
import com.example.integrationhub.exception.IntegrationHistoryNotFoundException;
import com.example.integrationhub.exception.IntegrationInProgressException;
import com.example.integrationhub.exception.IntegrationNotRetryableException;
import com.example.integrationhub.repository.IntegrationHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 주문을 ERP로 전송하고 MES에서 생산 가능 여부를 확인한 뒤 결과를 IntegrationHistory로 기록한다.
 * <p>
 * 이 클래스에는 @Transactional을 붙이지 않는다. 외부 호출(최대 Read Timeout까지 대기)하는 동안
 * DB 트랜잭션과 커넥션을 붙잡지 않도록 3단계로 나눈다.
 * 1) 주문 조회: OrderService.findById의 짧은 readOnly 트랜잭션
 * 2) ERP·MES 호출: 트랜잭션 없음 (일시적 오류는 RetryExecutor가 제한적으로 재시도)
 * 3) 이력 저장: Repository.save의 짧은 트랜잭션
 * <p>
 * 외부 시스템 오류는 예외로 전파하지 않고 FAILED 이력으로 바꾼다. 외부 장애가 core-api 500 오류로 번지지 않게 한다.
 * <p>
 * 중복 처리 방지: 같은 주문은 Redis 락으로 한 번에 하나만 실행하고, 이미 SUCCESS인 주문은 ERP를 다시 호출하지 않는다.
 */
@Service
public class IntegrationService {

    /** 감사 로그 전용 Logger. 로그 수집 시 이 이름으로 분리해 보관할 수 있다. 요청 ID는 MDC로 함께 찍힌다. */
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

    /** created=false: 이미 성공한 주문이라 새로 실행하지 않고 기존 SUCCESS 이력을 돌려준 경우 */
    public record SyncResult(IntegrationHistoryResponse history, boolean created) {
    }

    private final OrderService orderService;
    private final ErpClient erpClient;
    private final MesClient mesClient;
    private final IntegrationHistoryRepository historyRepository;
    private final RetryExecutor retryExecutor;
    private final IntegrationLock integrationLock;

    public IntegrationService(OrderService orderService, ErpClient erpClient, MesClient mesClient,
                              IntegrationHistoryRepository historyRepository, RetryExecutor retryExecutor,
                              IntegrationLock integrationLock) {
        this.orderService = orderService;
        this.erpClient = erpClient;
        this.mesClient = mesClient;
        this.historyRepository = historyRepository;
        this.retryExecutor = retryExecutor;
        this.integrationLock = integrationLock;
    }

    public SyncResult syncOrder(Long orderId, String actor) {
        return sync(orderId, new Request(actor, IntegrationTrigger.SYNC, null));
    }

    /**
     * 실패 재처리: FAILED 이력의 주문을 다시 연계하고 새 이력(trigger=RETRY, retryOf=원래 이력)을 만든다.
     * 기존 FAILED 이력은 그대로 남는다. 그 사이 다른 요청으로 이미 성공했다면 기존 SUCCESS 이력을 돌려준다.
     */
    public SyncResult retry(Long historyId, String actor) {
        IntegrationHistory history = historyRepository.findById(historyId)
                .orElseThrow(() -> new IntegrationHistoryNotFoundException(historyId));
        if (history.getStatus() != IntegrationStatus.FAILED) {
            throw new IntegrationNotRetryableException(historyId);
        }
        return sync(history.getOrderId(), new Request(actor, IntegrationTrigger.RETRY, historyId));
    }

    public Page<IntegrationHistoryResponse> findHistories(Long orderId, IntegrationStatus status, Pageable pageable) {
        return historyRepository.search(orderId, status, pageable).map(IntegrationHistoryResponse::from);
    }

    public IntegrationHistoryResponse findHistory(Long id) {
        return historyRepository.findById(id)
                .map(IntegrationHistoryResponse::from)
                .orElseThrow(() -> new IntegrationHistoryNotFoundException(id));
    }

    private record Request(String actor, IntegrationTrigger trigger, Long retryOf) {
    }

    private SyncResult sync(Long orderId, Request request) {
        OrderResponse order = orderService.findById(orderId);
        IntegrationLock.Handle lock = integrationLock.tryAcquire(orderId).orElseThrow(() -> {
            AUDIT_LOG.info("integration rejected: in progress orderId={} trigger={} actor={}",
                    orderId, request.trigger(), request.actor());
            return new IntegrationInProgressException(orderId);
        });
        try (lock) {
            // 락을 잡은 뒤에 확인해야, 동시에 들어온 요청이 둘 다 "아직 성공 안 함"으로 판단하는 경쟁을 막을 수 있다.
            Optional<IntegrationHistory> succeeded =
                    historyRepository.findFirstByOrderIdAndStatusOrderByIdDesc(orderId, IntegrationStatus.SUCCESS);
            if (succeeded.isPresent()) {
                AUDIT_LOG.info("integration skipped: already succeeded orderId={} historyId={} trigger={} actor={}",
                        orderId, succeeded.get().getId(), request.trigger(), request.actor());
                return new SyncResult(IntegrationHistoryResponse.from(succeeded.get()), false);
            }
            return new SyncResult(execute(order, request), true);
        }
    }

    private IntegrationHistoryResponse execute(OrderResponse order, Request request) {
        Long orderId = order.id();
        LocalDateTime requestedAt = LocalDateTime.now();

        RetryExecutor.Outcome<ErpOrderResponse> erp = retryExecutor.execute("ERP sendOrder order=" + orderId,
                () -> erpClient.sendOrder(ErpOrderRequest.from(order)), IntegrationService::isRetryable);
        if (!erp.succeeded()) {
            return record(failure(orderId, null, ExternalSystem.ERP, erp.error(), requestedAt,
                    auditOf(request, erp.attempts())));
        }

        // ERP는 이미 주문을 받았으므로 MES에서 실패해도 erpOrderNo를 남긴다 (재처리·보상 시 필요).
        String erpOrderNo = erp.value().erpOrderNo();
        RetryExecutor.Outcome<MesAvailabilityResponse> mes = retryExecutor.execute("MES checkAvailability order=" + orderId,
                () -> mesClient.checkAvailability(MesAvailabilityRequest.from(order)), IntegrationService::isRetryable);
        IntegrationHistory.Audit audit = auditOf(request, erp.attempts() + mes.attempts());
        if (!mes.succeeded()) {
            return record(failure(orderId, erpOrderNo, ExternalSystem.MES, mes.error(), requestedAt, audit));
        }
        if (!mes.value().producible()) {
            return record(IntegrationHistory.failure(orderId, erpOrderNo, ExternalSystem.MES,
                    IntegrationErrorType.REJECTED, mes.value().message(), requestedAt, audit));
        }
        return record(IntegrationHistory.success(orderId, erpOrderNo, requestedAt, audit));
    }

    private static IntegrationHistory.Audit auditOf(Request request, int attempts) {
        return new IntegrationHistory.Audit(request.actor(), request.trigger(), request.retryOf(), attempts);
    }

    private IntegrationHistoryResponse record(IntegrationHistory history) {
        IntegrationHistoryResponse saved = IntegrationHistoryResponse.from(historyRepository.save(history));
        AUDIT_LOG.info("integration {} historyId={} orderId={} trigger={} retryOf={} actor={} failedSystem={} errorType={} "
                        + "attempts={} durationMs={} erpOrderNo={}",
                saved.status(), saved.id(), saved.orderId(), saved.trigger(), saved.retryOf(), saved.requestedBy(),
                saved.failedSystem(), saved.errorType(), saved.attemptCount(), saved.durationMs(), saved.erpOrderNo());
        return saved;
    }

    /** 오류 메시지는 감싼 예외("Error while extracting response...")보다 근본 원인("Read timed out")이 더 유용하다. */
    private static IntegrationHistory failure(Long orderId, String erpOrderNo, ExternalSystem system,
                                              RestClientException e, LocalDateTime requestedAt,
                                              IntegrationHistory.Audit audit) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        String message = cause == e ? e.getMessage() : cause.toString();
        return IntegrationHistory.failure(orderId, erpOrderNo, system, classify(e), message, requestedAt, audit);
    }

    /**
     * 자동 재시도 대상: 다시 보내면 성공할 가능성이 있고, 중복 처리 위험이 없는 오류만.
     * - 502/503/504: 상대가 "지금은 처리 못 함"을 명시. ERP 요청에는 Idempotency-Key가 있어 중복 접수되지 않는다.
     * - 연결 거부·호스트 없음: 요청이 서버에 도달하지 않았으므로 안전하다.
     * 재시도하지 않는 오류:
     * - 4xx: 요청 자체가 잘못됨. 몇 번을 보내도 같다.
     * - 500: 상대 시스템의 버그일 가능성이 높다.
     * - Read Timeout, Connection reset: 상대가 이미 처리했을 수 있고, 느린 시스템에 부하를 더한다.
     * - 응답 해석 실패, 업무 거절(MES 생산 불가): 다시 보내도 결과가 같다.
     */
    static boolean isRetryable(RestClientException e) {
        if (e instanceof RestClientResponseException rre) {
            int status = rre.getStatusCode().value();
            return status == 502 || status == 503 || status == 504;
        }
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        return cause instanceof ConnectException || cause instanceof UnknownHostException;
    }

    /**
     * 예외 타입이 아니라 근본 원인으로 분류한다. Timeout·연결 끊김은 응답 본문을 읽는 도중에도 발생하는데,
     * 이때 RestClient는 ResourceAccessException이 아닌 일반 RestClientException으로 감싸기 때문이다.
     */
    static IntegrationErrorType classify(RestClientException e) {
        if (e instanceof RestClientResponseException rre) {
            return rre.getStatusCode().is5xxServerError()
                    ? IntegrationErrorType.SERVER_ERROR
                    : IntegrationErrorType.CLIENT_ERROR;
        }
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        if (cause instanceof SocketTimeoutException) {
            return IntegrationErrorType.TIMEOUT;
        }
        // ConnectException(연결 거부), "Connection reset" 등. JSON 파싱 오류(IOException 하위)와 구분하기 위해 SocketException만 본다.
        if (cause instanceof SocketException || cause instanceof UnknownHostException) {
            return IntegrationErrorType.CONNECTION_FAILED;
        }
        return IntegrationErrorType.INVALID_RESPONSE;
    }
}
