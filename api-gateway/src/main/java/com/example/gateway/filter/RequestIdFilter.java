package com.example.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청마다 X-Request-Id를 정해 core-api로 전달하고 응답 헤더에도 넣는다.
 * 클라이언트가 보낸 값은 형식이 안전할 때만 그대로 쓰고, 없거나 이상하면 새 UUID를 만든다 (로그 위조·과도한 길이 방지).
 * <p>
 * GlobalFilter는 Route가 매칭된 요청에만 실행되므로, 404·429 응답에도 적용되도록 WebFilter로 구현한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && VALID_REQUEST_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        ServerWebExchange mutated = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(HEADER, requestId)))
                .build();
        mutated.getAttributes().put(ATTRIBUTE, requestId);
        // core-api 응답 헤더 복사나 오류 응답 생성 이후에도 값이 남도록 응답 커밋 직전에 설정한다.
        mutated.getResponse().beforeCommit(() -> {
            mutated.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });
        return chain.filter(mutated);
    }
}
