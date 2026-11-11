package com.example.integrationhub;

import com.example.integrationhub.security.TestJwtIssuer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 테스트용 PostgreSQL 17, Redis 7.4 컨테이너. @ServiceConnection이 컨테이너 접속 정보를 Spring 설정으로 주입한다.
 * Flyway 마이그레이션이 그대로 실행되므로 운영과 같은 스키마로 테스트한다.
 * mock-erp, mock-mes는 docker compose와 같은 WireMock 매핑 파일(mock/*)을 사용한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final DockerImageName WIREMOCK_IMAGE = DockerImageName.parse("wiremock/wiremock:3.13.1");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    @Bean
    GenericContainer<?> mockErpContainer() {
        return wireMock("mock/erp/mappings");
    }

    @Bean
    GenericContainer<?> mockMesContainer() {
        return wireMock("mock/mes/mappings");
    }

    /**
     * Read Timeout을 1초로 줄여 Timeout 시나리오(Mock 지연 5초) 테스트 시간을 단축한다.
     * JWT 서명 검증용 JWKS는 TestJwtIssuer가 제공한다.
     */
    @Bean
    DynamicPropertyRegistrar mockSystemProperties(@Qualifier("mockErpContainer") GenericContainer<?> erp,
                                                  @Qualifier("mockMesContainer") GenericContainer<?> mes) {
        return registry -> {
            registry.add("integration.erp.base-url", () -> baseUrl(erp));
            registry.add("integration.mes.base-url", () -> baseUrl(mes));
            registry.add("integration.erp.read-timeout", () -> "1s");
            registry.add("integration.mes.read-timeout", () -> "1s");
            registry.add("OAUTH2_JWK_SET_URI", TestJwtIssuer::jwkSetUri);
        };
    }

    private static GenericContainer<?> wireMock(String mappingsDir) {
        return new GenericContainer<>(WIREMOCK_IMAGE)
                .withExposedPorts(8080)
                .withCopyFileToContainer(MountableFile.forHostPath(mappingsDir), "/home/wiremock/mappings")
                .waitingFor(Wait.forHttp("/__admin/health"));
    }

    private static String baseUrl(GenericContainer<?> container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(8080);
    }
}
