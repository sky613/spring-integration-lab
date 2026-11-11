package com.example.integrationhub.client;

import com.example.integrationhub.dto.OrderResponse;

import java.util.List;

/** MES에 재고·생산 가능 여부를 묻는 요청. 가격 정보는 MES에 필요 없으므로 보내지 않는다. */
public record MesAvailabilityRequest(Long orderId, String customerId, List<Item> items) {

    public record Item(String productName, int quantity) {
    }

    public static MesAvailabilityRequest from(OrderResponse order) {
        return new MesAvailabilityRequest(
                order.id(),
                order.customerId(),
                order.items().stream()
                        .map(item -> new Item(item.productName(), item.quantity()))
                        .toList());
    }
}
