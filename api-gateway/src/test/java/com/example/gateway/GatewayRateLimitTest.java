package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 버킷 크기 2, 초당 1개 충전으로 줄여 연속 요청 시 429가 나는지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"RATE_LIMIT_REPLENISH_RATE=1", "RATE_LIMIT_BURST_CAPACITY=2"})
@Import(GatewayTestContainers.class)
class GatewayRateLimitTest {

    @Autowired
    WebTestClient client;

    @Test
    void rejectsRequestsOverLimitWith429() {
        client.get().uri("/api/orders").exchange().expectStatus().isOk();
        client.get().uri("/api/orders").exchange().expectStatus().isOk();

        client.get().uri("/api/orders")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().exists("X-Request-Id");
    }
}
