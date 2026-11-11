package com.example.integrationhub.service;

import com.example.integrationhub.domain.OrderStatus;
import com.example.integrationhub.dto.OrderRequest;
import com.example.integrationhub.dto.OrderResponse;
import com.example.integrationhub.exception.InvalidOrderStatusException;
import com.example.integrationhub.exception.OrderNotFoundException;
import com.example.integrationhub.repository.InMemoryOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Constructor Injection 덕분에 Spring 없이 new로 의존성을 넣어 테스트할 수 있다. */
class OrderServiceTest {

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(new InMemoryOrderRepository());
    }

    private static OrderRequest request(String customerId) {
        return new OrderRequest(customerId, List.of(
                new OrderRequest.Item("keyboard", new BigDecimal("50000"), 2),
                new OrderRequest.Item("mouse", new BigDecimal("20000"), 1)));
    }

    @Test
    void createConvertsRequestToEntityAndReturnsResponse() {
        var response = orderService.create(request("customer-1"));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(response.totalAmount()).isEqualByComparingTo("120000");
        assertThat(response.items()).extracting(OrderResponse.Item::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("100000"), new BigDecimal("20000"));
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    void findAllAndFindById() {
        orderService.create(request("customer-1"));
        orderService.create(request("customer-2"));

        assertThat(orderService.findAll()).extracting(OrderResponse::customerId).containsExactly("customer-1", "customer-2");
        assertThat(orderService.findById(2L).customerId()).isEqualTo("customer-2");
        assertThatThrownBy(() -> orderService.findById(99L)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void changeStatusAppliesTransitionRules() {
        orderService.create(request("customer-1"));

        assertThat(orderService.changeStatus(1L, OrderStatus.PAID).status()).isEqualTo(OrderStatus.PAID);
        assertThatThrownBy(() -> orderService.changeStatus(1L, OrderStatus.CREATED))
                .isInstanceOf(InvalidOrderStatusException.class);
        assertThatThrownBy(() -> orderService.changeStatus(99L, OrderStatus.PAID))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void deleteRemovesOrder() {
        orderService.create(request("customer-1"));

        orderService.delete(1L);

        assertThat(orderService.findAll()).isEmpty();
        assertThatThrownBy(() -> orderService.delete(1L)).isInstanceOf(OrderNotFoundException.class);
    }
}
