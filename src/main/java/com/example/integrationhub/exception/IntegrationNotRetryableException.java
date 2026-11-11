package com.example.integrationhub.exception;

public class IntegrationNotRetryableException extends RuntimeException {

    public IntegrationNotRetryableException(Long historyId) {
        super("Only FAILED integration history can be retried: " + historyId);
    }
}
