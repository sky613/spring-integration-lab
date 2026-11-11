package com.example.integrationhub.exception;

public class IntegrationInProgressException extends RuntimeException {

    public IntegrationInProgressException(Long orderId) {
        super("Integration for order " + orderId + " is already in progress");
    }
}
