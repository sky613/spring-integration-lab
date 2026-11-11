package com.example.integrationhub.config;

import com.example.integrationhub.dto.InventoryResponse;
import com.example.integrationhub.service.InventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    /**
     * inventory 캐시: TTL 적용, 값은 사람이 읽을 수 있는 JSON으로 저장한다 (redis-cli로 확인 가능).
     * 기본(JDK 직렬화) 대신 타입을 지정한 JSON 직렬화를 써서 클래스 정보가 Redis에 남지 않게 한다.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer inventoryCacheCustomizer(ObjectMapper objectMapper,
                                                               @Value("${cache.inventory.ttl}") Duration ttl) {
        return builder -> builder.withCacheConfiguration(InventoryService.CACHE_NAME,
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(ttl)
                        .disableCachingNullValues()
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper, InventoryResponse.class))));
    }

    /** Redis 장애 시 캐시 오류로 API를 실패시키지 않고 경고만 남긴 뒤 DB에서 읽는다 (Fail-open). */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
