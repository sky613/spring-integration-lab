package com.example.integrationhub.exception;

import com.example.integrationhub.domain.OrderStatus;

public class InvalidOrderStatusException extends RuntimeException {

    public InvalidOrderStatusException(Long orderId, OrderStatus current, OrderStatus next) {
        super("Order %d cannot change status from %s to %s".formatted(orderId, current, next));
    }
}
