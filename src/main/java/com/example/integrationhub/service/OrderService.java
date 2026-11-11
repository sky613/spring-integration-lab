package com.example.integrationhub.service;

import com.example.integrationhub.domain.Order;
import com.example.integrationhub.domain.OrderStatus;
import com.example.integrationhub.dto.OrderRequest;
import com.example.integrationhub.dto.OrderResponse;
import com.example.integrationhub.exception.OrderNotFoundException;
import com.example.integrationhub.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    // [Constructor Injection] 생성자가 하나면 @Autowired 없이 주입된다. final 필드로 불변성과 테스트 용이성을 확보한다.
    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderResponse create(OrderRequest request) {
        return OrderResponse.from(orderRepository.save(request.toEntity()));
    }

    public List<OrderResponse> findAll() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::from)
                .toList();
    }

    public OrderResponse findById(Long id) {
        return OrderResponse.from(getOrder(id));
    }

    public OrderResponse changeStatus(Long id, OrderStatus status) {
        Order order = getOrder(id);
        order.changeStatus(status);
        return OrderResponse.from(orderRepository.save(order));
    }

    public void delete(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new OrderNotFoundException(id);
        }
        orderRepository.deleteById(id);
    }

    private Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }
}
