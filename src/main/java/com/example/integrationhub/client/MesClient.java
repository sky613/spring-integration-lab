package com.example.integrationhub.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** MES 재고·생산 가능 여부 확인 API 호출. 예외 규칙은 ErpClient와 같다. */
@Component
public class MesClient {

    private final RestClient restClient;

    public MesClient(@Qualifier("mesRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public MesAvailabilityResponse checkAvailability(MesAvailabilityRequest request) {
        MesAvailabilityResponse response = restClient.post()
                .uri("/api/production/availability")
                .body(request)
                .retrieve()
                .body(MesAvailabilityResponse.class);
        if (response == null) {
            throw new RestClientException("MES returned an empty response");
        }
        return response;
    }
}
