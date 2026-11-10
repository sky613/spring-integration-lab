package com.example.integrationhub.study.step1;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.integrationhub.study.step1.OrderFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

/** 명령형(Legacy) 구현과 Stream 구현이 같은 결과를 내는지 비교한다. */
class LegacyComparisonTest {

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(new InMemoryOrderRepository());
        service.create(request("c1", 1000));
        service.create(request("c1", 2000));
        service.create(request("c2", 3000));
        service.changeStatus(2L, OrderStatus.PAID);
        service.changeStatus(3L, OrderStatus.CANCELLED);
    }

    @Test
    void findByCustomer() {
        var orders = service.findAll();

        assertThat(LegacyOrderStatistics.findByCustomer(orders, "c1"))
                .isEqualTo(service.search(new OrderSearchCondition("c1", null, null)));
    }

    @Test
    void totalAmountByCustomer() {
        assertThat(LegacyOrderStatistics.totalAmountByCustomer(service.findAll()))
                .isEqualTo(service.totalAmountByCustomer());
    }

    @Test
    void groupByStatus() {
        assertThat(LegacyOrderStatistics.groupByStatus(service.findAll()))
                .isEqualTo(service.groupByStatus());
    }
}
