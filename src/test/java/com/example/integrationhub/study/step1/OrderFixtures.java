package com.example.integrationhub.study.step1;

import java.math.BigDecimal;
import java.util.List;

final class OrderFixtures {

    private OrderFixtures() {
    }

    /** 단가 price, 수량 1인 상품 하나로 구성된 주문 요청. */
    static CreateOrderRequest request(String customerId, long price) {
        return new CreateOrderRequest(customerId, List.of(new OrderItem("item", BigDecimal.valueOf(price), 1)));
    }
}
