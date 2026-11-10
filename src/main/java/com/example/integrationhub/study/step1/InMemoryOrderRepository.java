package com.example.integrationhub.study.step1;

public class InMemoryOrderRepository extends AbstractInMemoryRepository<Order, Long> {

    @Override
    protected Long idOf(Order order) {
        return order.getId();
    }
}
