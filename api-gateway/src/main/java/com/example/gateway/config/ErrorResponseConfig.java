package com.example.gateway.config;

import com.example.gateway.filter.RequestIdFilter;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class ErrorResponseConfig {

    /**
     * Gateway 오류 응답(404, 503, 504 등)의 requestId를 Spring 내부 ID 대신 X-Request-Id 값으로 바꾼다.
     * 클라이언트가 응답에서 본 값으로 Gateway·core-api 로그를 바로 찾을 수 있다.
     */
    @Bean
    ErrorAttributes errorAttributes() {
        return new DefaultErrorAttributes() {
            @Override
            public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
                Map<String, Object> attributes = super.getErrorAttributes(request, options);
                request.attribute(RequestIdFilter.ATTRIBUTE).ifPresent(id -> attributes.put("requestId", id));
                return attributes;
            }
        };
    }
}
