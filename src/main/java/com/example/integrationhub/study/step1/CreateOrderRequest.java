package com.example.integrationhub.study.step1;

import java.util.List;
import java.util.Objects;

/** [Record DTO] 주문 생성 요청. 외부에서 받은 List를 방어적으로 복사해 불변성을 보장한다. */
public record CreateOrderRequest(String customerId, List<OrderItem> items) {

    public CreateOrderRequest {
        Objects.requireNonNull(customerId, "customerId");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        items = List.copyOf(items);
    }
}
