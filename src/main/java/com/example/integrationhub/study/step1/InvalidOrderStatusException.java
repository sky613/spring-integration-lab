package com.example.integrationhub.study.step1;

public final class InvalidOrderStatusException extends OrderException {

    public InvalidOrderStatusException(Long orderId, OrderStatus current, OrderStatus next) {
        super("Order %d cannot change status from %s to %s".formatted(orderId, current, next));
    }
}
