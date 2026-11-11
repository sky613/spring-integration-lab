package com.example.integrationhub.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** application.yml의 integration.erp.*, integration.mes.* 설정. */
@ConfigurationProperties("integration")
public record IntegrationProperties(Endpoint erp, Endpoint mes) {

    /**
     * connectTimeout: TCP 연결 수립까지 기다리는 최대 시간 (서버가 꺼져 있거나 네트워크가 막힌 경우).
     * readTimeout: 연결 후 응답 데이터를 기다리는 최대 시간 (서버가 느린 경우).
     */
    public record Endpoint(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }
}
