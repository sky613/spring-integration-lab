package com.example.integrationhub.study.step1;

/**
 * [Exception Handling] 주문 도메인 예외의 공통 부모.
 * sealed로 하위 예외를 제한해 처리해야 할 예외 종류를 명확히 한다.
 * 비즈니스 예외는 호출자가 매번 catch를 강제받지 않도록 unchecked(RuntimeException)로 정의한다.
 */
public abstract sealed class OrderException extends RuntimeException
        permits OrderNotFoundException, InvalidOrderStatusException {

    protected OrderException(String message) {
        super(message);
    }
}
