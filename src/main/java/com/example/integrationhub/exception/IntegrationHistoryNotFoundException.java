package com.example.integrationhub.exception;

public class IntegrationHistoryNotFoundException extends RuntimeException {

    public IntegrationHistoryNotFoundException(Long historyId) {
        super("Integration history not found: " + historyId);
    }
}
