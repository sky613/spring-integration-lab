package com.example.integrationhub.study.step1;

public final class OrderNotFoundException extends OrderException {

    public OrderNotFoundException(Long orderId) {
        super("Order not found: " + orderId);
    }
}
