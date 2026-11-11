package com.example.integrationhub.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** POST /api/orders의 Idempotency-Key 기록. (owner, idempotencyKey)가 유일하다. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 키는 사용자별로 구분한다. 다른 사용자가 같은 키를 써도 서로의 주문이 보이지 않는다. */
    @Column(nullable = false, length = 100)
    private String owner;

    @Column(nullable = false, length = 100)
    private String idempotencyKey;

    /** 요청 본문의 SHA-256. 같은 키로 다른 내용을 보내면 거부한다. */
    @Column(nullable = false, length = 64)
    private String requestHash;

    private Long orderId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String owner, String idempotencyKey, String requestHash) {
        this.owner = owner;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void assignOrder(Long orderId) {
        this.orderId = orderId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Long getOrderId() {
        return orderId;
    }
}
