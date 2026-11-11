package com.example.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 모든 요청/응답을 한 줄씩 기록한다. 본문은 개인정보·크기 문제로 남기지 않는다.
 * 응답 로그는 커밋 직전(beforeCommit)에 남긴다. 오류 처리기가 만든 응답(404, 429, 503, 504)의 상태 코드도 기록하기 위해서다.
 * durationMs는 응답 헤더를 보내기까지 걸린 시간이다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startNanos = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();
        String requestId = exchange.getAttribute(RequestIdFilter.ATTRIBUTE);
        String path = request.getURI().getRawPath();

        log.info("request  requestId={} method={} path={} client={}",
                requestId, request.getMethod(), path, ClientIp.of(request));
        exchange.getResponse().beforeCommit(() -> {
            log.info("response requestId={} method={} path={} status={} durationMs={}",
                    requestId, request.getMethod(), path, exchange.getResponse().getStatusCode(),
                    (System.nanoTime() - startNanos) / 1_000_000);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
