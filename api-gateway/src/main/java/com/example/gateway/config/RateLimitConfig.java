package com.example.gateway.config;

import com.example.gateway.filter.ClientIp;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    /**
     * Rate Limit 버킷을 나누는 기준: 클라이언트 IP. (인증 도입 후에는 사용자 ID 기준으로 바꿀 수 있다.)
     * 기본 KeyResolver(PrincipalName)는 인증이 없으면 키가 비어 모든 요청을 403으로 거부한다.
     */
    @Bean
    KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.just(ClientIp.of(exchange.getRequest()));
    }
}
