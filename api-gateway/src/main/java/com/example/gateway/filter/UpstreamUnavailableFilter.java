package com.example.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;

/**
 * core-api에 연결할 수 없을 때(프로세스 중지, 컨테이너 없음) 기본 500 대신 503으로 응답한다.
 * 500은 "Gateway 자신의 오류", 503은 "뒤쪽 서비스를 지금 쓸 수 없음"이라 클라이언트가 재시도 여부를 판단할 수 있다.
 * 응답 Timeout은 Gateway가 이미 504로 응답하므로 여기서 다루지 않는다.
 */
@Component
public class UpstreamUnavailableFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange)
                .onErrorMap(e -> e instanceof ConnectException || e instanceof UnknownHostException,
                        e -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable", e));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
