package com.example.integrationhub.dto;

import com.example.integrationhub.domain.Order;
import com.example.integrationhub.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 주문 응답 DTO. Entity를 직접 반환하지 않고 API에 필요한 필드만 노출한다. */
public record OrderResponse(
        Long id,
        String customerId,
        OrderStatus status,
        List<Item> items,
        BigDecimal totalAmount,
        LocalDateTime createdAt) {

    public record Item(String productName, BigDecimal unitPrice, int quantity, BigDecimal amount) {
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getItems().stream()
                        .map(item -> new Item(item.productName(), item.unitPrice(), item.quantity(), item.amount()))
                        .toList(),
                order.totalAmount(),
                order.getCreatedAt());
    }
}
