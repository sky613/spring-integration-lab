package com.example.integrationhub.service;

import com.example.integrationhub.domain.IdempotencyRecord;
import com.example.integrationhub.dto.OrderRequest;
import com.example.integrationhub.dto.OrderResponse;
import com.example.integrationhub.exception.IdempotencyKeyMismatchException;
import com.example.integrationhub.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Idempotency-Key가 있는 주문 생성. 같은 사용자가 같은 키로 다시 요청하면(네트워크 재시도, 더블 클릭)
 * 새 주문을 만들지 않고 처음 만든 주문을 돌려준다(Replay).
 * <p>
 * 키 기록 INSERT와 주문 생성(재고 차감)을 한 트랜잭션에서 처리한다. 주문 생성이 실패(재고 부족 등)하면 키도 남지 않아
 * 같은 키로 다시 시도할 수 있다. 같은 키의 동시 요청은 DB unique 제약에서 대기하다가 먼저 커밋된 쪽의 결과를 받는다.
 */
@Service
public class IdempotentOrderService {

    public record Result(OrderResponse order, boolean replayed) {
    }

    private final OrderService orderService;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public IdempotentOrderService(OrderService orderService, IdempotencyRecordRepository idempotencyRepository,
                                  TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.idempotencyRepository = idempotencyRepository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public Result create(OrderRequest request, String owner, String idempotencyKey) {
        String requestHash = hash(request);
        try {
            return transactionTemplate.execute(status -> {
                var existing = idempotencyRepository.findByOwnerAndIdempotencyKey(owner, idempotencyKey);
                if (existing.isPresent()) {
                    return replay(existing.get(), requestHash, idempotencyKey);
                }
                // 키를 먼저 INSERT(flush)한다. 같은 키의 동시 요청은 여기서 대기하므로 재고를 건드리기 전에 걸러진다.
                IdempotencyRecord record = idempotencyRepository.saveAndFlush(
                        new IdempotencyRecord(owner, idempotencyKey, requestHash));
                OrderResponse created = orderService.create(request);
                record.assignOrder(created.id());
                return new Result(created, false);
            });
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 다른 요청이 먼저 커밋됨 → 그 결과를 돌려준다. 키 기록이 없으면 다른 제약 위반이므로 그대로 던진다.
            return transactionTemplate.execute(status -> idempotencyRepository
                    .findByOwnerAndIdempotencyKey(owner, idempotencyKey)
                    .map(record -> replay(record, requestHash, idempotencyKey))
                    .orElseThrow(() -> e));
        }
    }

    private Result replay(IdempotencyRecord record, String requestHash, String idempotencyKey) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyMismatchException(idempotencyKey);
        }
        return new Result(orderService.findById(record.getOrderId()), true);
    }

    private String hash(OrderRequest request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
