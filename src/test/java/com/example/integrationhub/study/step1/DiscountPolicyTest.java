package com.example.integrationhub.study.step1;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscountPolicyTest {

    private final BigDecimal amount = new BigDecimal("10000");

    @Test
    void sameMethodCallBehavesDifferentlyPerImplementation() {
        List<DiscountPolicy> policies = List.of(
                new DiscountPolicy.None(),
                new DiscountPolicy.Rate(15),
                new DiscountPolicy.Fixed(new BigDecimal("3000")));

        assertThat(policies).extracting(policy -> policy.apply(amount))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10000"), new BigDecimal("8500"), new BigDecimal("7000"));
    }

    @Test
    void fixedDiscountNeverGoesBelowZero() {
        assertThat(new DiscountPolicy.Fixed(new BigDecimal("20000")).apply(amount)).isEqualByComparingTo("0");
    }

    @Test
    void rateMustBeBetweenZeroAndHundred() {
        assertThatThrownBy(() -> new DiscountPolicy.Rate(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void describeUsesRecordPatternMatching() {
        assertThat(DiscountPolicy.describe(new DiscountPolicy.None())).isEqualTo("할인 없음");
        assertThat(DiscountPolicy.describe(new DiscountPolicy.Rate(10))).isEqualTo("10% 할인");
        assertThat(DiscountPolicy.describe(new DiscountPolicy.Fixed(new BigDecimal("500")))).isEqualTo("500원 할인");
    }
}
