package com.example.integrationhub.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String productName, int requested, int available) {
        super("Insufficient stock for %s: requested %d, available %d".formatted(productName, requested, available));
    }
}
