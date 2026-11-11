package com.example.integrationhub.repository;

import com.example.integrationhub.domain.Order;
import com.example.integrationhub.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA가 실행 시점에 구현체(Proxy)를 만든다. 메서드 이름으로 JPQL이 생성된다. */
public interface OrderRepository extends JpaRepository<Order, Long> {

    // select o from Order o where o.status = ?1 order by ... limit/offset (+ count 쿼리)
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
}
