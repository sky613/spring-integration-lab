package com.example.integrationhub.service;

import com.example.integrationhub.client.IntegrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 같은 주문의 연계를 동시에 하나만 실행하도록 하는 Redis 락 (SET key token NX PX ttl).
 * core-api 인스턴스가 여러 대여도 동작한다. 프로세스가 죽어도 TTL이 지나면 자동으로 풀린다.
 * 해제는 자신이 건 락(token 일치)일 때만 지운다. TTL이 지나 다른 요청이 새로 건 락을 지우지 않기 위해서다.
 */
@Component
public class IntegrationLock {

    private static final Logger log = LoggerFactory.getLogger(IntegrationLock.class);

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final IntegrationProperties properties;

    public IntegrationLock(StringRedisTemplate redis, IntegrationProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 이미 다른 요청이 처리 중이면 empty. Redis에 연결할 수 없으면 예외(→ 503)를 던진다 (중복 실행 위험이 있으므로 진행하지 않음). */
    public Optional<Handle> tryAcquire(Long orderId) {
        String key = "integration:lock:order:" + orderId;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, properties.lockTtl());
        return Boolean.TRUE.equals(acquired) ? Optional.of(new Handle(key, token)) : Optional.empty();
    }

    public final class Handle implements AutoCloseable {

        private final String key;
        private final String token;

        private Handle(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException e) {
                log.warn("Integration lock release failed (expires by TTL): key={}, cause={}", key, e.toString());
            }
        }
    }
}
