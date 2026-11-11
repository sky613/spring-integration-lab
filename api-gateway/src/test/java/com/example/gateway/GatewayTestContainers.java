package com.example.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Rate Limiter용 Redis와 core-api를 대신하는 WireMock 스텁 (src/test/resources/core-api-stub). */
@TestConfiguration(proxyBeanMethods = false)
public class GatewayTestContainers {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    @Bean
    GenericContainer<?> coreApiStub() {
        return new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.1"))
                .withExposedPorts(8080)
                .withCopyFileToContainer(MountableFile.forClasspathResource("core-api-stub/mappings"),
                        "/home/wiremock/mappings")
                .waitingFor(Wait.forHttp("/__admin/health"));
    }

    @Bean
    DynamicPropertyRegistrar coreApiUrl(@Qualifier("coreApiStub") GenericContainer<?> stub) {
        return registry -> registry.add("CORE_API_URL",
                () -> "http://" + stub.getHost() + ":" + stub.getMappedPort(8080));
    }
}
