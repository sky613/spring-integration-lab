package com.example.integrationhub.controller;

import com.example.integrationhub.TestcontainersConfiguration;
import com.example.integrationhub.client.ErpClient;
import com.example.integrationhub.client.MesClient;
import com.example.integrationhub.exception.IntegrationInProgressException;
import com.example.integrationhub.service.IntegrationService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
// 기능 테스트이므로 인증은 고정 사용자로 대체한다. 인증·인가 자체는 security/SecurityIntegrationTest에서 실제 JWT로 검증한다.
@WithMockUser(roles = {"USER", "ADMIN"})
class IntegrationApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    @Qualifier("mockErpContainer")
    GenericContainer<?> mockErp;

    @Autowired
    @Qualifier("mockMesContainer")
    GenericContainer<?> mockMes;

    @Autowired
    IntegrationService integrationService;

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
                .andExpect(jsonPath("$.erpOrderNo").value("ERP-" + orderId))
                // 감사 정보: @WithMockUser 기본 사용자 "user", ERP 1회 + MES 1회
                .andExpect(jsonPath("$.requestedBy").value("user"))
                .andExpect(jsonPath("$.trigger").value("SYNC"))
                .andExpect(jsonPath("$.retryOf").value(nullValue()))
                .andExpect(jsonPath("$.attemptCount").value(2));

        // 중복 Sync: 이미 성공한 주문은 ERP를 다시 호출하지 않고 기존 이력을 200으로 돌려준다
        mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(historyId));
        assertThat(countErpCalls(orderId)).isEqualTo(1);
    }

    /**
     * erpSucceeded: ERP 전송 후 MES에서 실패하면 ERP 주문 번호가 남아야 한다.
     * attempts/erpCalls: 503만 최대 3회까지 자동 재시도하고, 500·연결 끊김·업무 거절은 재시도하지 않는다.
     */
    @ParameterizedTest(name = "{0} → {1} {2}, attempts={4}")
    @CsvSource({
            "erp-error,       ERP, SERVER_ERROR,      false, 1, 1",
            "erp-reset,       ERP, CONNECTION_FAILED, false, 1, 1",
            "erp-unavailable, ERP, SERVER_ERROR,      false, 3, 3",
            "mes-error,       MES, SERVER_ERROR,      true,  4, 1",
            "mes-reject,      MES, REJECTED,          true,  2, 1",
    })
    void syncRecordsFailureEvent(String scenario, String failedSystem, String errorType, boolean erpSucceeded,
                                 int attempts, int erpCalls) throws Exception {
        int orderId = createOrder(scenario);

        mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedSystem").value(failedSystem))
                .andExpect(jsonPath("$.errorType").value(errorType))
                .andExpect(jsonPath("$.errorMessage").isNotEmpty())
                .andExpect(jsonPath("$.attemptCount").value(attempts))
                .andExpect(erpSucceeded
                        ? jsonPath("$.erpOrderNo").value("ERP-" + orderId)
                        : jsonPath("$.erpOrderNo").value(nullValue()));
        // 재시도해도 ERP에는 항상 같은 Idempotency-Key로 보낸다
        assertThat(countErpCalls(orderId)).isEqualTo(erpCalls);
    }

    /** erp-flaky: 첫 호출만 503 → 자동 재시도 2번째에 성공. ERP 2회 + MES 1회. */
    @Test
    void transientErpFailureIsRecoveredByAutomaticRetry() throws Exception {
        int orderId = createOrder("erp-flaky");

        mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.attemptCount").value(3));
        assertThat(countErpCalls(orderId)).isEqualTo(2);
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
                // Read Timeout은 재시도하지 않는다 (상대가 이미 처리했을 수 있고, 느린 시스템에 부하를 더한다)
                .andExpect(jsonPath("$.attemptCount").value(failedSystem.equals("ERP") ? 1 : 2))
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

    /**
     * erp-outage: 처음 3번 503 → 자동 재시도(최대 3회)를 넘겨 FAILED로 남는다.
     * 장애 복구 후 재처리 API로 다시 실행하면 새 이력이 SUCCESS(trigger=RETRY, retryOf=원래 이력)로 생기고 기존 FAILED 이력은 그대로 남는다.
     */
    @Test
    void failedHistoryStaysFailedAfterMaxAttemptsAndCanBeRetriedManually() throws Exception {
        int orderId = createOrder("erp-outage");
        String failed = mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorType").value("SERVER_ERROR"))
                .andExpect(jsonPath("$.attemptCount").value(3))
                .andReturn().getResponse().getContentAsString();
        int failedId = JsonPath.read(failed, "$.id");

        String retried = mockMvc.perform(post("/api/integrations/history/{id}/retry", failedId))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/integrations/history/")))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.erpOrderNo").value("ERP-" + orderId))
                .andExpect(jsonPath("$.trigger").value("RETRY"))
                .andExpect(jsonPath("$.retryOf").value(failedId))
                .andExpect(jsonPath("$.requestedBy").value("user"))
                .andReturn().getResponse().getContentAsString();
        int successId = JsonPath.read(retried, "$.id");
        assertThat(successId).isNotEqualTo(failedId);

        mockMvc.perform(get("/api/integrations/history/{id}", failedId))
                .andExpect(jsonPath("$.status").value("FAILED"));
        // 같은 FAILED 이력을 다시 재처리해도 이미 성공했으므로 ERP를 호출하지 않고 성공 이력을 돌려준다
        mockMvc.perform(post("/api/integrations/history/{id}/retry", failedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(successId));
        assertThat(countErpCalls(orderId)).isEqualTo(4);
        // 성공한 이력은 재처리 대상이 아니다
        mockMvc.perform(post("/api/integrations/history/{id}/retry", successId))
                .andExpect(status().isConflict());
    }

    /** 같은 주문을 동시에 Sync하면 하나만 실행되고 나머지는 409(진행 중). erp-timeout으로 첫 요청이 약 1초간 락을 잡는다. */
    @Test
    void concurrentSyncOfSameOrderRunsOnlyOnce() throws Exception {
        long orderId = createOrder("erp-timeout");
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> sync = () -> {
            start.await();
            try {
                return integrationService.syncOrder(orderId, "concurrent-user");
            } catch (IntegrationInProgressException e) {
                return e;
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<Object>> futures = List.of(executor.submit(sync), executor.submit(sync), executor.submit(sync));
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(r -> r instanceof IntegrationService.SyncResult).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof IntegrationInProgressException).hasSize(2);
        } finally {
            executor.shutdownNow();
        }
        assertThat(countErpCalls(orderId)).isEqualTo(1);
        // 락이 해제된 뒤에는 다시 실행할 수 있다
        assertThat(integrationService.syncOrder(orderId, "concurrent-user").created()).isTrue();
    }

    @Test
    void retryUnknownHistoryReturns404() throws Exception {
        mockMvc.perform(post("/api/integrations/history/{id}/retry", 999_999))
                .andExpect(status().isNotFound());
    }

    /** Gateway가 보낸 요청 ID가 core-api를 거쳐 ERP, MES 요청 헤더까지 전달된다. WireMock 요청 기록으로 확인한다. */
    @Test
    void propagatesRequestIdToErpAndMes() throws Exception {
        int orderId = createOrder("customer-req-id");
        String requestId = "sync-req-" + orderId;

        mockMvc.perform(post("/api/integrations/orders/{id}/sync", orderId).header("X-Request-Id", requestId))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-Id", requestId));

        assertThat(countRequestsWithRequestId(mockErp, requestId)).isEqualTo(1);
        assertThat(countRequestsWithRequestId(mockMes, requestId)).isEqualTo(1);
    }

    private static int countRequestsWithRequestId(GenericContainer<?> wireMock, String requestId) {
        return countRequestsWithHeader(wireMock, "X-Request-Id", requestId);
    }

    /** ERP는 같은 주문에 항상 같은 Idempotency-Key(order-{id})를 받으므로 이 값으로 주문별 호출 수를 센다. */
    private int countErpCalls(long orderId) {
        return countRequestsWithHeader(mockErp, "Idempotency-Key", "order-" + orderId);
    }

    private static int countRequestsWithHeader(GenericContainer<?> wireMock, String header, String value) {
        Map<?, ?> result = RestClient.builder()
                .baseUrl("http://" + wireMock.getHost() + ":" + wireMock.getMappedPort(8080))
                // 기본 JDK HttpClient의 h2c 업그레이드 요청을 WireMock이 끊는 문제를 피한다 (운영 코드와 같은 HttpURLConnection 사용).
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build()
                .post().uri("/__admin/requests/count")
                .body(Map.of("method", "POST", "headers", Map.of(header, Map.of("equalTo", value))))
                .retrieve()
                .body(Map.class);
        return (Integer) result.get("count");
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
