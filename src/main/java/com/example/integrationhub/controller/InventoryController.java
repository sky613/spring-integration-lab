package com.example.integrationhub.controller;

import com.example.integrationhub.dto.InventoryResponse;
import com.example.integrationhub.service.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** 상품별 재고 조회 (Redis 캐시). 없는 상품은 404. */
    @GetMapping("/{productName}")
    public InventoryResponse findByProductName(@PathVariable String productName) {
        return inventoryService.findByProductName(productName);
    }
}
