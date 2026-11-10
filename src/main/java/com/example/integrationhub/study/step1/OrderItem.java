package com.example.integrationhub.study.step1;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * [Record] 불변 값 객체. 생성자, accessor, equals/hashCode/toString이 자동 생성된다.
 * Compact Constructor에서 생성 시점 검증을 수행한다.
 */
public record OrderItem(String productName, BigDecimal unitPrice, int quantity) {

    public OrderItem {
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
