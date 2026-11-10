package com.example.integrationhub.study.step1;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * [Interface / Polymorphism] 같은 apply() 호출이 구현체마다 다르게 동작한다.
 * sealed interface + record로 구현체를 제한하면 switch 패턴 매칭에서 default 없이 모든 경우를 검사할 수 있다.
 */
public sealed interface DiscountPolicy {

    BigDecimal apply(BigDecimal amount);

    record None() implements DiscountPolicy {
        @Override
        public BigDecimal apply(BigDecimal amount) {
            return amount;
        }
    }

    record Rate(int percent) implements DiscountPolicy {
        public Rate {
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("percent must be between 0 and 100");
            }
        }

        @Override
        public BigDecimal apply(BigDecimal amount) {
            return amount.multiply(BigDecimal.valueOf(100 - percent))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
        }
    }

    record Fixed(BigDecimal discount) implements DiscountPolicy {
        @Override
        public BigDecimal apply(BigDecimal amount) {
            return amount.subtract(discount).max(BigDecimal.ZERO);
        }
    }

    /** [Java 21 Record Pattern] instanceof + 캐스팅 없이 타입 분기와 필드 추출을 한 번에 처리한다. */
    static String describe(DiscountPolicy policy) {
        return switch (policy) {
            case None none -> "할인 없음";
            case Rate(int percent) -> percent + "% 할인";
            case Fixed(BigDecimal discount) -> discount.toPlainString() + "원 할인";
        };
    }
}
