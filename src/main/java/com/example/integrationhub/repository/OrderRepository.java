package com.example.integrationhub.repository;

import com.example.integrationhub.domain.Order;

import java.util.List;
import java.util.Optional;

/** Service는 이 Interface에만 의존한다. 이후 JPA 구현으로 교체해도 Service 코드는 바뀌지 않는다. */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(Long id);

    List<Order> findAll();

    boolean existsById(Long id);

    void deleteById(Long id);
}
