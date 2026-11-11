package com.example.integrationhub.service;

import com.example.integrationhub.client.ErpClient;
import com.example.integrationhub.client.ErpOrderRequest;
import com.example.integrationhub.client.ErpOrderResponse;
import com.example.integrationhub.client.MesAvailabilityRequest;
import com.example.integrationhub.client.MesAvailabilityResponse;
import com.example.integrationhub.client.MesClient;
import com.example.integrationhub.domain.ExternalSystem;
import com.example.integrationhub.domain.IntegrationErrorType;
import com.example.integrationhub.domain.IntegrationHistory;
import com.example.integrationhub.domain.IntegrationStatus;
import com.example.integrationhub.dto.IntegrationHistoryResponse;
import com.example.integrationhub.dto.OrderResponse;
import com.example.integrationhub.exception.IntegrationHistoryNotFoundException;
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

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;

/**
 * 주문을 ERP로 전송하고 MES에서 생산 가능 여부를 확인한 뒤 결과를 IntegrationHistory로 기록한다.
 * <p>
 * 이 클래스에는 @Transactional을 붙이지 않는다. 외부 호출(최대 Read Timeout까지 대기)하는 동안
 * DB 트랜잭션과 커넥션을 붙잡지 않도록 3단계로 나눈다.
 * 1) 주문 조회: OrderService.findById의 짧은 readOnly 트랜잭션
 * 2) ERP·MES 호출: 트랜잭션 없음
 * 3) 이력 저장: Repository.save의 짧은 트랜잭션
 * <p>
 * 외부 시스템 오류는 예외로 전파하지 않고 FAILED 이력으로 바꾼다. 외부 장애가 core-api 500 오류로 번지지 않게 한다.
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    private final OrderService orderService;
    private final ErpClient erpClient;
    private final MesClient mesClient;
    private final IntegrationHistoryRepository historyRepository;

    public IntegrationService(OrderService orderService, ErpClient erpClient, MesClient mesClient,
                              IntegrationHistoryRepository historyRepository) {
        this.orderService = orderService;
        this.erpClient = erpClient;
        this.mesClient = mesClient;
        this.historyRepository = historyRepository;
    }

    public IntegrationHistoryResponse syncOrder(Long orderId) {
        OrderResponse order = orderService.findById(orderId);
        LocalDateTime requestedAt = LocalDateTime.now();

        ErpOrderResponse erp;
        try {
            erp = erpClient.sendOrder(ErpOrderRequest.from(order));
        } catch (RestClientException e) {
            return record(failure(orderId, null, ExternalSystem.ERP, e, requestedAt));
        }

        // ERP는 이미 주문을 받았으므로 MES에서 실패해도 erpOrderNo를 남긴다 (재처리·보상 시 필요).
        MesAvailabilityResponse mes;
        try {
            mes = mesClient.checkAvailability(MesAvailabilityRequest.from(order));
        } catch (RestClientException e) {
            return record(failure(orderId, erp.erpOrderNo(), ExternalSystem.MES, e, requestedAt));
        }
        if (!mes.producible()) {
            return record(IntegrationHistory.failure(orderId, erp.erpOrderNo(), ExternalSystem.MES,
                    IntegrationErrorType.REJECTED, mes.message(), requestedAt));
        }
        return record(IntegrationHistory.success(orderId, erp.erpOrderNo(), requestedAt));
    }

    /** FAILED 이력의 주문을 다시 연계한다. 기존 이력은 그대로 두고 새 이력을 만든다 (재처리 기록도 남는다). */
    public IntegrationHistoryResponse retry(Long historyId) {
        IntegrationHistory history = historyRepository.findById(historyId)
                .orElseThrow(() -> new IntegrationHistoryNotFoundException(historyId));
        if (history.getStatus() != IntegrationStatus.FAILED) {
            throw new IntegrationNotRetryableException(historyId);
        }
        return syncOrder(history.getOrderId());
    }

    public Page<IntegrationHistoryResponse> findHistories(Long orderId, IntegrationStatus status, Pageable pageable) {
        return historyRepository.search(orderId, status, pageable).map(IntegrationHistoryResponse::from);
    }

    public IntegrationHistoryResponse findHistory(Long id) {
        return historyRepository.findById(id)
                .map(IntegrationHistoryResponse::from)
                .orElseThrow(() -> new IntegrationHistoryNotFoundException(id));
    }

    private IntegrationHistoryResponse record(IntegrationHistory history) {
        if (history.getStatus() == IntegrationStatus.FAILED) {
            log.warn("Integration failed: orderId={}, system={}, errorType={}, message={}",
                    history.getOrderId(), history.getFailedSystem(), history.getErrorType(), history.getErrorMessage());
        }
        return IntegrationHistoryResponse.from(historyRepository.save(history));
    }

    /** 오류 메시지는 감싼 예외("Error while extracting response...")보다 근본 원인("Read timed out")이 더 유용하다. */
    private static IntegrationHistory failure(Long orderId, String erpOrderNo, ExternalSystem system,
                                              RestClientException e, LocalDateTime requestedAt) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        String message = cause == e ? e.getMessage() : cause.toString();
        return IntegrationHistory.failure(orderId, erpOrderNo, system, classify(e), message, requestedAt);
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
