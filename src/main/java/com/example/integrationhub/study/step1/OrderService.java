package com.example.integrationhub.study.step1;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** 주문 생성, 상태 변경, Stream 기반 검색/집계를 담당한다. */
public class OrderService {

    private final Repository<Order, Long> repository;
    private final AtomicLong sequence = new AtomicLong();

    // 구현체가 아닌 Interface에 의존해 저장소를 교체할 수 있게 한다.
    public OrderService(Repository<Order, Long> repository) {
        this.repository = repository;
    }

    public Order create(CreateOrderRequest request) {
        var order = new Order(sequence.incrementAndGet(), request.customerId(), request.items());
        return repository.save(order);
    }

    public List<Order> findAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(Order::getId))
                .toList();
    }

    public Optional<Order> findById(Long orderId) {
        return repository.findById(orderId);
    }

    public Order getById(Long orderId) {
        return repository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public Order changeStatus(Long orderId, OrderStatus next) {
        var order = getById(orderId);
        order.changeStatus(next);
        return order;
    }

    public List<Order> search(OrderSearchCondition condition) {
        return findAll().stream()
                .filter(condition::matches)
                .toList();
    }

    /** 고객별 주문 금액 합계. 취소된 주문은 제외한다. */
    public Map<String, BigDecimal> totalAmountByCustomer() {
        return repository.findAll().stream()
                .filter(order -> order.getStatus() != OrderStatus.CANCELLED)
                .collect(Collectors.groupingBy(
                        Order::getCustomerId,
                        Collectors.reducing(BigDecimal.ZERO, Order::totalAmount, BigDecimal::add)));
    }

    /** 주문 상태별 그룹화. EnumMap은 Enum 선언 순서를 유지하고 HashMap보다 효율적이다. */
    public Map<OrderStatus, List<Order>> groupByStatus() {
        return findAll().stream()
                .collect(Collectors.groupingBy(
                        Order::getStatus,
                        () -> new EnumMap<>(OrderStatus.class),
                        Collectors.toList()));
    }

    public Optional<Order> findLargestOrder() {
        return repository.findAll().stream()
                .max(Comparator.comparing(Order::totalAmount));
    }

    public BigDecimal payableAmount(Long orderId, DiscountPolicy policy) {
        return policy.apply(getById(orderId).totalAmount());
    }
}
