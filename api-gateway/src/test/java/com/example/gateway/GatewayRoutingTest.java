package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 Gateway(Netty) → WireMock(core-api 스텁) HTTP 경로로 라우팅, 요청 ID, 노출 제한, Timeout을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "GATEWAY_RESPONSE_TIMEOUT=1s")
@Import(GatewayTestContainers.class)
class GatewayRoutingTest {

    @Autowired
    WebTestClient client;

    @LocalServerPort
    int port;

    @Test
    void routesOrderApiToCoreApiAndGeneratesRequestId() {
        var result = client.get().uri("/api/orders")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "0")
                .expectHeader().exists("X-Request-Id")
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectBody().json("[]")
                .returnResult();

        String requestId = result.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(requestId).matches("[0-9a-f-]{36}");
        // core-api가 받은 값과 클라이언트가 받은 값이 같다
        assertThat(result.getResponseHeaders().getFirst("X-Received-Request-Id")).isEqualTo(requestId);
    }

    @Test
    void keepsValidClientRequestId() {
        client.get().uri("/api/orders")
                .header("X-Request-Id", "client-req-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "client-req-123")
                .expectHeader().valueEquals("X-Received-Request-Id", "client-req-123");
    }

    /** 허용 문자([A-Za-z0-9-]) 이외의 문자나 64자 초과 값은 버리고 새로 만든다. */
    @ParameterizedTest
    @ValueSource(strings = {"bad id; rm -rf", "<script>",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}) // 65자
    void replacesUnsafeClientRequestId(String unsafe) {
        var headers = client.get().uri("/api/orders")
                .header("X-Request-Id", unsafe)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders();

        assertThat(headers.getFirst("X-Request-Id")).matches("[0-9a-f-]{36}");
        assertThat(headers.getFirst("X-Received-Request-Id")).isEqualTo(headers.getFirst("X-Request-Id"));
    }

    /** core-api가 Host 헤더로 만드는 Location이 내부 주소가 아니라 클라이언트가 접속한 Gateway 주소여야 한다. */
    @Test
    void forwardsRequestBodyAndKeepsClientHostInLocation() {
        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"customerId\":\"customer-1\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "http://localhost:" + port + "/api/orders/1")
                .expectBody().json("{\"customerId\":\"customer-1\"}");
    }

    /**
     * ERP/MES 경로와 core-api 내부 Actuator는 Route가 없으므로 core-api에 전달되지 않는다.
     * (/actuator/health는 Gateway 자신의 Health라서 제외. 스텁은 /actuator/** 에 200을 주지만 도달하지 않는다.)
     */
    @Test
    void doesNotExposeInternalPaths() {
        for (String path : new String[]{"/actuator/env", "/actuator/metrics", "/api/production/availability",
                "/erp/api/orders", "/mes/api/production/availability"}) {
            client.get().uri(path)
                    .exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().exists("X-Request-Id");
        }
    }

    @Test
    void returns504WhenCoreApiIsTooSlow() {
        client.get().uri("/api/orders/slow")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    void gatewayHealthIncludesRedis() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.redis.status").isEqualTo("UP");
    }
}
