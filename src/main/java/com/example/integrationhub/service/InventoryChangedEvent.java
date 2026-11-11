package com.example.integrationhub.service;

import java.util.Set;

/** 재고 수량이 바뀐 상품. 트랜잭션 커밋 후 캐시 무효화에 사용한다. */
public record InventoryChangedEvent(Set<String> productNames) {
}
