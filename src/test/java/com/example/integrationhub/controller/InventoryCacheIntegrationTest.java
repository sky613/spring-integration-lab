package com.example.integrationhub.controller;

import com.example.integrationhub.TestcontainersConfiguration;
import com.example.integrationhub.service.InventoryService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 재고 조회 Redis 캐시: Cache Aside, TTL, 재고 변경(주문·취소) 후 무효화를 실제 Redis와 PostgreSQL로 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@WithMockUser(roles = {"USER", "ADMIN"})
class InventoryCacheIntegrationTest {

    private static final String KEY = "inventory::monitor";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    CacheManager cacheManager;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearCache() {
        Objects.requireNonNull(cacheManager.getCache(InventoryService.CACHE_NAME)).clear();
    }

    @Test
    void firstReadStoresJsonInRedisWithTtl() throws Exception {
        assertThat(redis.hasKey(KEY)).isFalse();

        mockMvc.perform(get("/api/inventory/monitor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("monitor"));

        assertThat(redis.opsForValue().get(KEY)).contains("\"productName\":\"monitor\"").contains("\"quantity\":");
        Long ttl = redis.getExpire(KEY);
        assertThat(ttl).isBetween(1L, 60L);
    }

    /** DB를 직접 바꿔도(캐시 무효화 없이) TTL 동안은 캐시 값을 돌려준다 = 두 번째 조회는 DB가 아니라 Redis에서 읽는다. */
    @Test
    void secondReadIsServedFromCache() throws Exception {
        int cached = readQuantity();
        jdbcTemplate.update("update inventory set quantity = quantity + 100 where product_name = 'monitor'");
        try {
            assertThat(readQuantity()).isEqualTo(cached);
        } finally {
            jdbcTemplate.update("update inventory set quantity = quantity - 100 where product_name = 'monitor'");
        }
    }

    /** 주문(재고 차감)과 취소(재고 복구) 커밋 후 캐시가 지워져 다음 조회는 DB의 최신 값을 읽는다. */
    @Test
    void orderAndCancelEvictCache() throws Exception {
        int before = readQuantity();
        assertThat(redis.hasKey(KEY)).isTrue();

        String body = mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId": "customer-1", "items": [{"productName": "monitor", "unitPrice": 300000, "quantity": 2}]}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(redis.hasKey(KEY)).isFalse();
        assertThat(readQuantity()).isEqualTo(before - 2);

        int orderId = JsonPath.read(body, "$.id");
        mockMvc.perform(patch("/api/orders/{id}/status", orderId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());
        assertThat(redis.hasKey(KEY)).isFalse();
        assertThat(readQuantity()).isEqualTo(before);
    }

    /** 실패한 주문(재고 부족 → Rollback)은 재고가 바뀌지 않았으므로 캐시를 지우지 않는다. */
    @Test
    void rolledBackOrderDoesNotEvictCache() throws Exception {
        readQuantity();

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId": "customer-1", "items": [{"productName": "monitor", "unitPrice": 300000, "quantity": 999999}]}
                        """))
                .andExpect(status().isConflict());

        assertThat(redis.hasKey(KEY)).isTrue();
    }

    @Test
    void unknownProductReturns404AndIsNotCached() throws Exception {
        mockMvc.perform(get("/api/inventory/tablet"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Product not found: tablet"));

        assertThat(redis.hasKey("inventory::tablet")).isFalse();
    }

    private int readQuantity() throws Exception {
        String body = mockMvc.perform(get("/api/inventory/monitor"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.quantity");
    }
}
