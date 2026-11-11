package com.example.integrationhub.controller;

import com.example.integrationhub.TestcontainersConfiguration;
import com.example.integrationhub.dto.OrderRequest;
import com.example.integrationhub.service.IdempotentOrderService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/orders의 Idempotency-Key: 중복 요청 방지와 재고 1회 차감을 실제 DB로 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@WithMockUser(roles = "USER")
class OrderIdempotencyIntegrationTest {

    private static final String MOUSE_ORDER = """
            {"customerId": "customer-1", "items": [{"productName": "mouse", "unitPrice": 20000, "quantity": 1}]}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IdempotentOrderService idempotentOrderService;

    @Test
    void sameKeyReturnsSameOrderAndDecreasesStockOnce() throws Exception {
        String key = "key-" + UUID.randomUUID();
        int stockBefore = mouseStock();

        String first = create(key, MOUSE_ORDER)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        int orderId = JsonPath.read(first, "$.id");

        create(key, MOUSE_ORDER)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "http://localhost/api/orders/" + orderId))
                .andExpect(jsonPath("$.id").value(orderId));

        assertThat(mouseStock()).isEqualTo(stockBefore - 1);
    }

    @Test
    void sameKeyWithDifferentBodyReturns422() throws Exception {
        String key = "key-" + UUID.randomUUID();
        create(key, MOUSE_ORDER).andExpect(status().isCreated());

        create(key, MOUSE_ORDER.replace("\"quantity\": 1", "\"quantity\": 2"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key was already used with a different request: " + key));
    }

    /** 키는 사용자별이다. 다른 사용자가 같은 키를 써도 서로의 주문을 받지 않는다. */
    @Test
    void sameKeyFromDifferentUsersCreatesSeparateOrders() throws Exception {
        String key = "shared-key-" + UUID.randomUUID();
        int alice = JsonPath.read(mockMvc.perform(post("/api/orders").with(user("alice").roles("USER"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(MOUSE_ORDER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        int bob = JsonPath.read(mockMvc.perform(post("/api/orders").with(user("bob").roles("USER"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(MOUSE_ORDER))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        assertThat(bob).isNotEqualTo(alice);
    }

    /** 키가 없으면 기존 동작 그대로 매번 새 주문 (하위 호환) */
    @Test
    void withoutKeyEachRequestCreatesNewOrder() throws Exception {
        int first = JsonPath.read(create(null, MOUSE_ORDER).andReturn().getResponse().getContentAsString(), "$.id");
        int second = JsonPath.read(create(null, MOUSE_ORDER).andReturn().getResponse().getContentAsString(), "$.id");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void invalidKeyFormatReturns400() throws Exception {
        create("has space", MOUSE_ORDER).andExpect(status().isBadRequest());
        create("k".repeat(101), MOUSE_ORDER).andExpect(status().isBadRequest());
    }

    /** 재고 부족으로 실패한 요청은 키가 남지 않으므로, 같은 키로 다시 시도할 수 있다 (실패 결과를 고정하지 않음). */
    @Test
    void failedRequestDoesNotConsumeKey() throws Exception {
        String key = "key-" + UUID.randomUUID();
        create(key, MOUSE_ORDER.replace("\"quantity\": 1", "\"quantity\": 999999"))
                .andExpect(status().isConflict());

        create(key, MOUSE_ORDER)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    /** 같은 키로 동시에 5건이 들어와도 주문은 1건, 재고 차감도 1회. 나머지는 먼저 커밋된 결과를 받는다. */
    @Test
    void concurrentRequestsWithSameKeyCreateOneOrder() throws Exception {
        String key = "key-" + UUID.randomUUID();
        OrderRequest request = new OrderRequest("customer-1",
                List.of(new OrderRequest.Item("mouse", new BigDecimal("20000"), 1)));
        int stockBefore = mouseStock();
        CountDownLatch start = new CountDownLatch(1);
        Callable<IdempotentOrderService.Result> call = () -> {
            start.await();
            return idempotentOrderService.create(request, "concurrent-user", key);
        };

        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<IdempotentOrderService.Result> results = new ArrayList<>();
        try {
            List<Future<IdempotentOrderService.Result>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(call));
            }
            start.countDown();
            for (Future<IdempotentOrderService.Result> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(results).extracting(r -> r.order().id()).containsOnly(results.getFirst().order().id());
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(mouseStock()).isEqualTo(stockBefore - 1);
    }

    private ResultActions create(String key, String body) throws Exception {
        var request = post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private int mouseStock() {
        return jdbcTemplate.queryForObject("select quantity from inventory where product_name = 'mouse'", Integer.class);
    }
}
