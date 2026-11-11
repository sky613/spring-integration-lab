package com.example.integrationhub.dto;

import com.example.integrationhub.domain.Inventory;

/** 재고 조회 응답. Redis 캐시에도 이 형태(JSON)로 저장된다. */
public record InventoryResponse(String productName, int quantity) {

    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(inventory.getProductName(), inventory.getQuantity());
    }
}
