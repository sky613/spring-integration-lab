package com.example.integrationhub.exception;

public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException(String idempotencyKey) {
        super("Idempotency-Key was already used with a different request: " + idempotencyKey);
    }
}
