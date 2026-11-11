package com.example.integrationhub.exception;

/** 재고 조회 대상 상품이 없음 (404). 주문 요청의 알 수 없는 상품은 UnknownProductException(400)을 쓴다. */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String productName) {
        super("Product not found: " + productName);
    }
}
