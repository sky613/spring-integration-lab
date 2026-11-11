package com.example.integrationhub.repository;

import com.example.integrationhub.domain.Order;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 임시 In-Memory 저장소. Bean은 기본적으로 Singleton이므로 여러 요청 Thread가 동시에 접근한다.
 * shortcut: 애플리케이션 재시작 시 데이터가 사라진다. 영속 저장이 필요해지면 JPA 구현으로 교체한다.
 */
@Repository
public class InMemoryOrderRepository implements OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryOrderRepository.class);

    private final Map<Long, Order> store = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /** [Bean Lifecycle] 생성자 호출과 의존성 주입이 끝난 뒤 한 번 호출된다. */
    @PostConstruct
    void init() {
        log.info("InMemoryOrderRepository initialized");
    }

    /** [Bean Lifecycle] ApplicationContext가 종료될 때 호출된다. */
    @PreDestroy
    void destroy() {
        log.info("InMemoryOrderRepository destroyed: clearing {} orders", store.size());
        store.clear();
    }

    @Override
    public Order save(Order order) {
        if (order.getId() == null) {
            order.assignId(sequence.incrementAndGet());
        }
        store.put(order.getId(), order);
        return order;
    }

    @Override
    public Optional<Order> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Order> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(Order::getId))
                .toList();
    }

    @Override
    public boolean existsById(Long id) {
        return store.containsKey(id);
    }

    @Override
    public void deleteById(Long id) {
        store.remove(id);
    }
}
