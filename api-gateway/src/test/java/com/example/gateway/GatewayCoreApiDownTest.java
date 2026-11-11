package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** core-api가 꺼져 있을 때(연결 거부) Gateway가 죽지 않고 503으로 응답하는지 확인한다. 포트 1은 리스닝하는 프로세스가 없다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "CORE_API_URL=http://localhost:1")
@Import(GatewayCoreApiDownTest.RedisOnly.class)
class GatewayCoreApiDownTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisOnly {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
        }
    }

    @Autowired
    WebTestClient client;

    /** 오류 본문의 requestId가 X-Request-Id 헤더와 같아야 클라이언트가 보고한 값으로 로그를 찾을 수 있다. */
    @Test
    void returns503WhenCoreApiIsDown() {
        client.get().uri("/api/orders")
                .header("X-Request-Id", "down-req-1")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("X-Request-Id", "down-req-1")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.requestId").isEqualTo("down-req-1");
    }
}
