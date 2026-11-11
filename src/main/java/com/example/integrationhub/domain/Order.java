package com.example.integrationhub.domain;

import com.example.integrationhub.exception.InvalidOrderStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 주문 Entity. 식별자(id)와 상태를 가지며 상태 변경 규칙을 스스로 지킨다.
 * API 요청/응답 형식(DTO)과 분리되어 있으므로 API가 바뀌어도 도메인 규칙은 영향을 받지 않는다.
 */
public class Order {

    private Long id;
    private final String customerId;
    private final List<OrderItem> items;
    private final LocalDateTime createdAt;
    private OrderStatus status;

    public Order(String customerId, List<OrderItem> items) {
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.items = List.copyOf(items);
        this.createdAt = LocalDateTime.now();
        this.status = OrderStatus.CREATED;
    }

    /** 저장소가 저장 시점에 식별자를 부여한다 (JPA의 @GeneratedValue 역할). */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id already assigned: " + this.id);
        }
        this.id = id;
    }

    public synchronized void changeStatus(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidOrderStatusException(id, status, next);
        }
        this.status = next;
    }

    public BigDecimal totalAmount() {
        return items.stream()
                .map(OrderItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public synchronized OrderStatus getStatus() {
        return status;
    }
}
