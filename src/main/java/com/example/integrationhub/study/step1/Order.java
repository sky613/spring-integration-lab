package com.example.integrationhub.study.step1;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * [Class / Encapsulation] 상태(status)는 private으로 숨기고 changeStatus()로만 변경한다.
 * setter를 열어두면 전이 규칙을 우회할 수 있으므로 의도한 행위만 메서드로 노출한다.
 */
public class Order {

    private final Long id;
    private final String customerId;
    private final List<OrderItem> items;
    private OrderStatus status;

    public Order(Long id, String customerId, List<OrderItem> items) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.items = List.copyOf(items);
        this.status = OrderStatus.CREATED;
    }

    // 여러 Thread가 동시에 상태를 바꿀 수 있으므로 검사와 변경을 원자적으로 수행한다.
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

    public synchronized OrderStatus getStatus() {
        return status;
    }
}
