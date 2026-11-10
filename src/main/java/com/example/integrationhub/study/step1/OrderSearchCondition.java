package com.example.integrationhub.study.step1;

import java.math.BigDecimal;

/** [Record] 주문 검색 조건. null 필드는 "조건 없음"을 의미한다. */
public record OrderSearchCondition(String customerId, OrderStatus status, BigDecimal minAmount) {

    public static OrderSearchCondition all() {
        return new OrderSearchCondition(null, null, null);
    }

    public boolean matches(Order order) {
        return (customerId == null || customerId.equals(order.getCustomerId()))
                && (status == null || status == order.getStatus())
                && (minAmount == null || order.totalAmount().compareTo(minAmount) >= 0);
    }
}
