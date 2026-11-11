package com.example.integrationhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 재고를 바꾼 트랜잭션이 커밋된 뒤에 캐시를 지운다.
 * 커밋 전에 지우면, 커밋되기 전에 다른 요청이 DB의 이전 값을 읽어 다시 캐시에 넣을 수 있다.
 * Redis 장애로 지우지 못해도 주문 처리(이미 커밋됨)는 실패시키지 않는다. 오래된 값은 TTL 안에 만료된다.
 */
@Component
public class InventoryCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(InventoryCacheEvictor.class);

    private final CacheManager cacheManager;

    public InventoryCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void evict(InventoryChangedEvent event) {
        Cache cache = cacheManager.getCache(InventoryService.CACHE_NAME);
        if (cache == null) {
            return;
        }
        for (String productName : event.productNames()) {
            try {
                cache.evict(productName);
            } catch (RuntimeException e) {
                log.warn("Inventory cache eviction failed (expires by TTL): product={}, cause={}", productName, e.toString());
            }
        }
    }
}
