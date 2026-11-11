package com.example.integrationhub.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 외부 시스템마다 Base URL과 Timeout이 다르므로 RestClient를 각각 만든다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationClientConfig {

    @Bean
    RestClient erpRestClient(RestClient.Builder builder, IntegrationProperties properties) {
        return create(builder, properties.erp());
    }

    @Bean
    RestClient mesRestClient(RestClient.Builder builder, IntegrationProperties properties) {
        return create(builder, properties.mes());
    }

    private static RestClient create(RestClient.Builder builder, IntegrationProperties.Endpoint endpoint) {
        // Timeout을 지정하지 않으면 무한 대기할 수 있다. 외부 장애가 core-api 스레드 고갈로 전파되는 것을 막는다.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(endpoint.connectTimeout());
        requestFactory.setReadTimeout(endpoint.readTimeout());
        // Builder는 prototype Bean이지만 다른 설정과 섞이지 않도록 복제해서 사용한다.
        return builder.clone()
                .baseUrl(endpoint.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
