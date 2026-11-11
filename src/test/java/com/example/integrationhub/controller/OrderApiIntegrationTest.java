package com.example.integrationhub.controller;

import com.example.integrationhub.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 Bean(Controller → Service → Repository → PostgreSQL)을 연결한 상태에서 CRUD 전체 흐름을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// 기능 테스트이므로 인증은 고정 사용자로 대체한다. 인증·인가 자체는 security/SecurityIntegrationTest에서 실제 JWT로 검증한다.
@WithMockUser(roles = {"USER", "ADMIN"})
class OrderApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void crudFlow() throws Exception {
        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "customer-1",
                                 "items": [{"productName": "keyboard", "unitPrice": 50000, "quantity": 2}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/orders").param("status", "CREATED").param("sort", "id,desc"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$[0].id").value(id));

        mockMvc.perform(patch("/api/orders/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "PAID"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(100000));

        mockMvc.perform(delete("/api/orders/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isNotFound());
    }
}
