package com.example.integrationhub.controller;

import com.example.integrationhub.domain.OrderStatus;
import com.example.integrationhub.dto.OrderRequest;
import com.example.integrationhub.dto.OrderResponse;
import com.example.integrationhub.exception.InvalidOrderStatusException;
import com.example.integrationhub.exception.OrderNotFoundException;
import com.example.integrationhub.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web 계층(Controller, Validation, ExceptionHandler)만 로드하고 Service는 Mock으로 대체한다. */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderService orderService;

    private static OrderResponse response(Long id, OrderStatus status) {
        return new OrderResponse(id, "customer-1", status,
                List.of(new OrderResponse.Item("keyboard", new BigDecimal("50000"), 2, new BigDecimal("100000"))),
                new BigDecimal("100000"), LocalDateTime.of(2026, 11, 11, 10, 0));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        given(orderService.create(any(OrderRequest.class))).willReturn(response(1L, OrderStatus.CREATED));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "customer-1",
                                 "items": [{"productName": "keyboard", "unitPrice": 50000, "quantity": 2}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/orders/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(100000));
    }

    @Test
    void createReturns400WithFieldErrorsWhenInvalid() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "",
                                 "items": [{"productName": "keyboard", "unitPrice": -1, "quantity": 0}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("customerId", "items[0].unitPrice", "items[0].quantity")));

        verify(orderService, never()).create(any());
    }

    @Test
    void createReturns400WhenItemsEmpty() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "customer-1", "items": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items"));
    }

    @Test
    void findAllReturns200() throws Exception {
        given(orderService.findAll()).willReturn(List.of(response(1L, OrderStatus.CREATED), response(2L, OrderStatus.PAID)));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].status").value("PAID"));
    }

    @Test
    void findByIdReturns200() throws Exception {
        given(orderService.findById(1L)).willReturn(response(1L, OrderStatus.CREATED));

        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("customer-1"))
                .andExpect(jsonPath("$.items[0].amount").value(100000));
    }

    @Test
    void findByIdReturns404WhenMissing() throws Exception {
        given(orderService.findById(99L)).willThrow(new OrderNotFoundException(99L));

        mockMvc.perform(get("/api/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Order not found: 99"));
    }

    @Test
    void findByIdReturns400WhenIdIsNotNumber() throws Exception {
        mockMvc.perform(get("/api/orders/abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changeStatusReturns200() throws Exception {
        given(orderService.changeStatus(1L, OrderStatus.PAID)).willReturn(response(1L, OrderStatus.PAID));

        mockMvc.perform(patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "PAID"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void changeStatusReturns409WhenTransitionNotAllowed() throws Exception {
        given(orderService.changeStatus(1L, OrderStatus.DELIVERED))
                .willThrow(new InvalidOrderStatusException(1L, OrderStatus.CREATED, OrderStatus.DELIVERED));

        mockMvc.perform(patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "DELIVERED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order 1 cannot change status from CREATED to DELIVERED"));
    }

    @Test
    void changeStatusReturns400WhenStatusMissingOrUnknown() throws Exception {
        mockMvc.perform(patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));

        mockMvc.perform(patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "UNKNOWN"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/orders/1"))
                .andExpect(status().isNoContent());

        verify(orderService).delete(1L);
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        willThrow(new OrderNotFoundException(99L)).given(orderService).delete(99L);

        mockMvc.perform(delete("/api/orders/99"))
                .andExpect(status().isNotFound());
    }
}
