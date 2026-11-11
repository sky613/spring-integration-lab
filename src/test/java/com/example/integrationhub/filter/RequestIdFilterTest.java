package com.example.integrationhub.filter;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void usesIncomingRequestIdInMdcAndResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "gw-req-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcDuringRequest.set(MDC.get("requestId")));

        assertThat(mdcDuringRequest.get()).isEqualTo("gw-req-1");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("gw-req-1");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void generatesRequestIdWhenMissingOrUnsafe() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "bad id\nforged");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    }
}
