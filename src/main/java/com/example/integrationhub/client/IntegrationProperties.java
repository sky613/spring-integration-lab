package com.example.integrationhub.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** application.yml의 integration.* 설정. */
@ConfigurationProperties("integration")
public record IntegrationProperties(Endpoint erp, Endpoint mes, Retry retry, Duration lockTtl) {

    /**
     * connectTimeout: TCP 연결 수립까지 기다리는 최대 시간 (서버가 꺼져 있거나 네트워크가 막힌 경우).
     * readTimeout: 연결 후 응답 데이터를 기다리는 최대 시간 (서버가 느린 경우).
     */
    public record Endpoint(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }

    /**
     * 외부 호출 1건(ERP 전송 또는 MES 확인)의 자동 재시도 정책.
     * maxAttempts: 첫 시도 포함 최대 호출 수. 대기 시간은 initialBackoff부터 multiplier배씩 늘고 maxBackoff를 넘지 않는다.
     * maxElapsed: 다음 시도를 시작할 시점이 첫 시도부터 이 시간을 넘으면 더 시도하지 않는다 (Gateway Timeout보다 짧게).
     */
    public record Retry(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff,
                        Duration maxElapsed) {
    }
}
