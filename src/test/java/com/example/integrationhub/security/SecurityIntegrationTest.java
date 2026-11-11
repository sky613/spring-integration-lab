package com.example.integrationhub.security;

import com.example.integrationhub.TestcontainersConfiguration;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증(401)·인가(403) 통합 테스트. @WithMockUser 없이 실제 서명된 JWT를 Authorization 헤더로 보내
 * 운영과 같은 검증(서명, 만료, issuer, audience)과 Realm Role → 권한 변환, URL별 권한 규칙을 통과하는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIntegrationTest {

    private static final String ORDER_JSON = """
            {"customerId": "customer-1",
             "items": [{"productName": "keyboard", "unitPrice": 50000, "quantity": 1}]}
            """;

    @Autowired
    MockMvc mockMvc;

    // ---- 401: 인증 실패 ----

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.instance").value("/api/orders"));
    }

    static Stream<Arguments> invalidTokens() {
        Instant past = Instant.now().minusSeconds(3600);
        return Stream.of(
                Arguments.of("malformed", "not-a-jwt"),
                Arguments.of("expired", TestJwtIssuer.sign(TestJwtIssuer.claims("user1", "USER")
                        .issueTime(Date.from(past.minusSeconds(300)))
                        .expirationTime(Date.from(past)).build())),
                Arguments.of("wrong issuer", TestJwtIssuer.sign(TestJwtIssuer.claims("user1", "USER")
                        .issuer("http://evil.example/realms/integration-hub").build())),
                Arguments.of("wrong audience", TestJwtIssuer.sign(TestJwtIssuer.claims("user1", "USER")
                        .audience(List.of("other-api")).build())),
                Arguments.of("untrusted signing key", TestJwtIssuer.signWithUntrustedKey(
                        TestJwtIssuer.claims("user1", "USER").build())));
    }

    @ParameterizedTest(name = "{0} → 401")
    @MethodSource("invalidTokens")
    void invalidTokenReturns401(String reason, String token) throws Exception {
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Invalid or expired access token"))
                // 검증 실패의 내부 사유는 본문에 노출하지 않는다
                .andExpect(content().string(not(containsString("Jwt"))));
    }

    // ---- 역할별 권한 (RBAC) ----

    @Test
    void userCanReadAndCreateOrders() throws Exception {
        String user = TestJwtIssuer.token("user1", "USER");

        mockMvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_JSON))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/orders/{id}", 999_999).header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isNotFound()); // 인가 통과 후 Controller까지 도달
    }

    static Stream<Arguments> adminOnlyRequests() {
        return Stream.of(
                Arguments.of("GET history", get("/api/integrations/history")),
                Arguments.of("GET history/{id}", get("/api/integrations/history/1")),
                Arguments.of("POST retry", post("/api/integrations/history/1/retry")),
                Arguments.of("POST sync", post("/api/integrations/orders/1/sync")),
                Arguments.of("PATCH order status", patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}")),
                Arguments.of("DELETE order", delete("/api/orders/1")));
    }

    @ParameterizedTest(name = "USER: {0} → 403")
    @MethodSource("adminOnlyRequests")
    void userCannotCallAdminApis(String name, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token("user1", "USER")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void adminCanReadIntegrationHistory() throws Exception {
        mockMvc.perform(get("/api/integrations/history")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token("admin1", "USER", "ADMIN")))
                .andExpect(status().isOk());
    }

    /** ADMIN 역할만 있고 USER 역할이 없으면 주문 API는 쓸 수 없다 (역할을 상속하지 않고 Keycloak에서 명시적으로 부여). */
    @Test
    void adminOnlyRoleCannotUseUserApis() throws Exception {
        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token("ops", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void validTokenWithoutRolesReturns403() throws Exception {
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token("nobody")))
                .andExpect(status().isForbidden());
    }

    /** 허용 목록에 없는 경로는 유효한 토큰이 있어도 거부한다 (denyAll). */
    @Test
    void unlistedPathIsDeniedEvenForAdmin() throws Exception {
        mockMvc.perform(get("/actuator/env")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token("admin1", "USER", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
