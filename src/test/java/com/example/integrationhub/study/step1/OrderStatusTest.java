package com.example.integrationhub.study.step1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED, PAID, true",
            "CREATED, CANCELLED, true",
            "CREATED, SHIPPED, false",
            "PAID, SHIPPED, true",
            "PAID, CANCELLED, true",
            "SHIPPED, DELIVERED, true",
            "SHIPPED, CANCELLED, false",
            "DELIVERED, CANCELLED, false",
            "CANCELLED, PAID, false"
    })
    void canTransitionTo(OrderStatus current, OrderStatus next, boolean expected) {
        assertThat(current.canTransitionTo(next)).isEqualTo(expected);
    }

    @Test
    void enumProvidesDescriptionAndValueOf() {
        assertThat(OrderStatus.valueOf("PAID").getDescription()).isEqualTo("결제 완료");
        assertThat(OrderStatus.values()).hasSize(5);
    }
}
