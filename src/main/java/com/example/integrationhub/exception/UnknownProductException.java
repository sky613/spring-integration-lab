package com.example.integrationhub.exception;

public class UnknownProductException extends RuntimeException {

    public UnknownProductException(String productName) {
        super("Unknown product: " + productName);
    }
}
