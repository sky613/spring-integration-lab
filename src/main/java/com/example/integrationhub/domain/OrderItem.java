package com.example.integrationhub.domain;

import java.math.BigDecimal;

public record OrderItem(String productName, BigDecimal unitPrice, int quantity) {

    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
