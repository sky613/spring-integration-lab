package com.example.integrationhub.study.step1;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.example.integrationhub.study.step1.OrderFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest {

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(new InMemoryOrderRepository());
    }

    @Test
    void createAssignsSequentialIdAndCreatedStatus() {
        var order = service.create(new CreateOrderRequest("c1", List.of(
                new OrderItem("keyboard", new BigDecimal("50000"), 1),
                new OrderItem("mouse", new BigDecimal("20000"), 2))));

        assertThat(order.getId()).isEqualTo(1L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.totalAmount()).isEqualByComparingTo("90000");
    }

    @Test
    void findAllReturnsOrdersSortedById() {
        service.create(request("c1", 1000));
        service.create(request("c2", 2000));
        service.create(request("c3", 3000));

        assertThat(service.findAll()).extracting(Order::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void changeStatusFollowsTransitionRules() {
        var order = service.create(request("c1", 1000));

        service.changeStatus(order.getId(), OrderStatus.PAID);
        service.changeStatus(order.getId(), OrderStatus.SHIPPED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThatThrownBy(() -> service.changeStatus(order.getId(), OrderStatus.CANCELLED))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessageContaining("SHIPPED to CANCELLED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void missingOrderIsEmptyOptionalOrException() {
        assertThat(service.findById(99L)).isEmpty();
        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(OrderNotFoundException.class)
                .isInstanceOf(OrderException.class);
    }

    @Test
    void optionalChainingWithoutNullCheck() {
        service.create(request("c1", 1000));

        String customer = service.findById(1L).map(Order::getCustomerId).orElse("unknown");
        String missing = service.findById(2L).map(Order::getCustomerId).orElse("unknown");

        assertThat(customer).isEqualTo("c1");
        assertThat(missing).isEqualTo("unknown");
    }

    @Test
    void searchCombinesConditions() {
        service.create(request("c1", 1000));
        service.create(request("c1", 5000));
        service.create(request("c2", 7000));
        service.changeStatus(2L, OrderStatus.PAID);

        assertThat(service.search(new OrderSearchCondition("c1", null, null)))
                .extracting(Order::getId).containsExactly(1L, 2L);
        assertThat(service.search(new OrderSearchCondition(null, null, new BigDecimal("5000"))))
                .extracting(Order::getId).containsExactly(2L, 3L);
        assertThat(service.search(new OrderSearchCondition("c1", OrderStatus.PAID, null)))
                .extracting(Order::getId).containsExactly(2L);
        assertThat(service.search(OrderSearchCondition.all())).hasSize(3);
    }

    @Test
    void totalAmountByCustomerExcludesCancelledOrders() {
        service.create(request("c1", 1000));
        service.create(request("c1", 2000));
        service.create(request("c2", 5000));
        service.create(request("c2", 9000));
        service.changeStatus(4L, OrderStatus.CANCELLED);

        var totals = service.totalAmountByCustomer();

        assertThat(totals).containsOnlyKeys("c1", "c2");
        assertThat(totals.get("c1")).isEqualByComparingTo("3000");
        assertThat(totals.get("c2")).isEqualByComparingTo("5000");
    }

    @Test
    void groupByStatusKeepsEnumOrder() {
        service.create(request("c1", 1000));
        service.create(request("c1", 2000));
        service.create(request("c2", 3000));
        service.changeStatus(1L, OrderStatus.PAID);
        service.changeStatus(3L, OrderStatus.CANCELLED);

        var groups = service.groupByStatus();

        assertThat(groups.keySet()).containsExactly(OrderStatus.CREATED, OrderStatus.PAID, OrderStatus.CANCELLED);
        assertThat(groups.get(OrderStatus.PAID)).extracting(Order::getId).containsExactly(1L);
    }

    @Test
    void findLargestOrderAndPayableAmount() {
        assertThat(service.findLargestOrder()).isEmpty();

        service.create(request("c1", 1000));
        service.create(request("c2", 8000));

        assertThat(service.findLargestOrder()).map(Order::getId).contains(2L);
        assertThat(service.payableAmount(2L, new DiscountPolicy.Rate(10))).isEqualByComparingTo("7200");
    }
}
