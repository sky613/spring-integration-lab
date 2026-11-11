package com.example.integrationhub.service;

import com.example.integrationhub.dto.InventoryResponse;
import com.example.integrationhub.exception.ProductNotFoundException;
import com.example.integrationhub.repository.InventoryRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cache Aside: 먼저 Redis에서 찾고(hit), 없으면(miss) DB에서 읽어 Redis에 넣은 뒤 반환한다.
 * 재고가 바뀌면 InventoryCacheEvictor가 커밋 후 해당 키를 지운다. 지우지 못해도 TTL이 지나면 만료된다.
 */
@Service
public class InventoryService {

    public static final String CACHE_NAME = "inventory";

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "#productName")
    @Transactional(readOnly = true)
    public InventoryResponse findByProductName(String productName) {
        return inventoryRepository.findByProductName(productName)
                .map(InventoryResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(productName));
    }
}
