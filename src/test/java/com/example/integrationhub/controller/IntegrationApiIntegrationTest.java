package com.example.integrationhub.controller;

import com.example.integrationhub.TestcontainersConfiguration;
import com.example.integrationhub.client.ErpClient;
import com.example.integrationhub.client.MesClient;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * core-api → mock-erp, mock-mes(WireMock 컨테이너) 실제 HTTP 연계를 검증한다.
 * 장애 시나리오는 주문의 customerId로 선택한다 (mock/erp, mock/mes 매핑 참고).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IntegrationApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoSpyBean
    ErpClient erpClient;

    @MockitoSpyBean
    MesClient mesClient;

    /** 모든 테스트에서 외부 호출 시점에 DB 트랜잭션이 열려 있지 않은지 확인한 뒤 실제 호출을 진행한다. */
    @BeforeEach
    void assertNoTransactionDuringExternalCalls() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(erpClient).sendOrder(any());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(mesClient).checkAvailability(any());
    }

    @Test
    void syncSucceedsWhenErpAndMesSucceed() throws Exception {
        int orderId = createOrder("customer-1");

        String body = mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/integrations/history/")))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.erpOrderNo").value("ERP-" + orderId))
                .andExpect(jsonPath("$.failedSystem").value(nullValue()))
                .andExpect(jsonPath("$.errorType").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        int historyId = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/integrations/history/{id}", historyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.erpOrderNo").value("ERP-" + orderId));
    }

    /** erpSucceeded: ERP 전송 후 MES에서 실패하면 ERP 주문 번호가 남아야 한다. */
    @ParameterizedTest(name = "{0} → {1} {2}")
    @CsvSource({
            "erp-error,   ERP, SERVER_ERROR,      false",
            "erp-reset,   ERP, CONNECTION_FAILED, false",
            "mes-error,   MES, SERVER_ERROR,      true",
            "mes-reject,  MES, REJECTED,          true",
    })
    void syncRecordsFailureEvent(String scenario, String failedSystem, String errorType, boolean erpSucceeded)
            throws Exception {
        int orderId = createOrder(scenario);

        mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedSystem").value(failedSystem))
                .andExpect(jsonPath("$.errorType").value(errorType))
                .andExpect(jsonPath("$.errorMessage").isNotEmpty())
                .andExpect(erpSucceeded
                        ? jsonPath("$.erpOrderNo").value("ERP-" + orderId)
                        : jsonPath("$.erpOrderNo").value(nullValue()));
    }

    /** Mock은 5초 뒤 응답하지만 Read Timeout(테스트 1초)에서 끊고 TIMEOUT으로 기록한다. */
    @ParameterizedTest
    @CsvSource({"erp-timeout, ERP", "mes-timeout, MES"})
    void syncRecordsTimeoutWithoutWaitingForSlowSystem(String scenario, String failedSystem) throws Exception {
        int orderId = createOrder(scenario);

        mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedSystem").value(failedSystem))
                .andExpect(jsonPath("$.errorType").value("TIMEOUT"))
                .andExpect(jsonPath("$.errorMessage", containsString("timed out")))
                .andExpect(jsonPath("$.durationMs", greaterThanOrEqualTo(1000)))
                .andExpect(jsonPath("$.durationMs", lessThan(5000)));
    }

    @Test
    void syncUnknownOrderReturns404() throws Exception {
        mockMvc.perform(post("/api/integrations/orders/{id}/sync", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Order not found: 999999"));
    }

    @Test
    void historyCanBeFilteredByOrderAndStatus() throws Exception {
        int orderId = createOrder("erp-error");
        mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/integrations/history").param("orderId", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$[0].orderId").value(orderId));

        mockMvc.perform(get("/api/integrations/history")
                        .param("orderId", String.valueOf(orderId))
                        .param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"));

        mockMvc.perform(get("/api/integrations/history").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void unknownHistoryReturns404() throws Exception {
        mockMvc.perform(get("/api/integrations/history/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", endsWith("999999")));
    }

    private int createOrder(String customerId) throws Exception {
        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "%s",
                                 "items": [{"productName": "keyboard", "unitPrice": 50000, "quantity": 1}]}
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
