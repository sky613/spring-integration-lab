package com.example.integrationhub.study.step1;

import java.math.BigDecimal;

/** [Record DTO] 주문 조회 응답. 도메인 객체(Order)를 외부에 그대로 노출하지 않기 위해 사용한다. */
public record OrderSummary(Long orderId, String customerId, OrderStatus status, BigDecimal totalAmount) {

    public static OrderSummary from(Order order) {
        return new OrderSummary(order.getId(), order.getCustomerId(), order.getStatus(), order.totalAmount());
    }
}
