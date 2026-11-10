package com.example.integrationhub.study.step1;

/**
 * [Enum] 주문 상태와 상태 전이 규칙을 한 곳에서 관리한다.
 * Java 8 이전처럼 int/String 상수를 쓰면 잘못된 값이 들어와도 컴파일러가 막지 못한다.
 */
public enum OrderStatus {
    CREATED("주문 생성"),
    PAID("결제 완료"),
    SHIPPED("배송 중"),
    DELIVERED("배송 완료"),
    CANCELLED("주문 취소");

    private final String description;

    OrderStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** [Switch Expression] 모든 상수를 다루지 않으면 컴파일 오류가 나므로 상태 추가 시 누락을 막는다. */
    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case CREATED -> next == PAID || next == CANCELLED;
            case PAID -> next == SHIPPED || next == CANCELLED;
            case SHIPPED -> next == DELIVERED;
            case DELIVERED, CANCELLED -> false;
        };
    }
}
