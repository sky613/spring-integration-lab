package com.example.integrationhub.client;

import com.example.integrationhub.dto.OrderResponse;

import java.math.BigDecimal;
import java.util.List;

/** ERP로 전송하는 주문 정보 (ERP 인터페이스 규격). core-api 내부 모델과 분리한다. */
public record ErpOrderRequest(Long orderId, String customerId, List<Item> items, BigDecimal totalAmount) {

    public record Item(String productName, int quantity, BigDecimal unitPrice) {
    }

    public static ErpOrderRequest from(OrderResponse order) {
        return new ErpOrderRequest(
                order.id(),
                order.customerId(),
                order.items().stream()
                        .map(item -> new Item(item.productName(), item.quantity(), item.unitPrice()))
                        .toList(),
                order.totalAmount());
    }
}
