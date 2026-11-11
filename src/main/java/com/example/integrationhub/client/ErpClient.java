package com.example.integrationhub.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * ERP 주문 전송 API 호출.
 * 4xx/5xx는 RestClientResponseException, Timeout·연결 오류는 ResourceAccessException(RestClientException 하위)으로 던진다.
 */
@Component
public class ErpClient {

    private final RestClient restClient;

    public ErpClient(@Qualifier("erpRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public ErpOrderResponse sendOrder(ErpOrderRequest request) {
        ErpOrderResponse response = restClient.post()
                .uri("/api/orders")
                // 같은 주문은 항상 같은 키. 자동 Retry나 재처리로 다시 보내도 ERP가 중복 접수하지 않도록 한다.
                .header("Idempotency-Key", "order-" + request.orderId())
                .body(request)
                .retrieve()
                .body(ErpOrderResponse.class);
        if (response == null || response.erpOrderNo() == null) {
            throw new RestClientException("ERP returned no erpOrderNo");
        }
        return response;
    }
}
