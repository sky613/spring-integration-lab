package com.example.integrationhub.service;

import com.example.integrationhub.domain.Inventory;
import com.example.integrationhub.domain.Order;
import com.example.integrationhub.domain.OrderItem;
import com.example.integrationhub.domain.OrderStatus;
import com.example.integrationhub.dto.OrderRequest;
import com.example.integrationhub.dto.OrderResponse;
import com.example.integrationhub.exception.OrderNotFoundException;
import com.example.integrationhub.exception.UnknownProductException;
import com.example.integrationhub.repository.InventoryRepository;
import com.example.integrationhub.repository.OrderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * 클래스 기본값은 readOnly 트랜잭션(조회 최적화: Dirty Checking 생략, Flush 안 함)이고,
 * 데이터를 바꾸는 메서드만 @Transactional로 덮어쓴다.
 * RuntimeException이 발생하면 트랜잭션 전체(재고 차감 + 주문 저장)가 Rollback된다.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, InventoryRepository inventoryRepository,
                        ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        // 여러 트랜잭션이 같은 순서로 Lock을 잡도록 상품명 순으로 차감해 Deadlock을 피한다.
        request.items().stream()
                .sorted(Comparator.comparing(OrderRequest.Item::productName))
                .forEach(item -> lockInventory(item.productName()).decrease(item.quantity()));
        // 재고 캐시는 커밋 후 무효화한다 (InventoryCacheEvictor). Rollback되면 이벤트도 전달되지 않는다.
        eventPublisher.publishEvent(new InventoryChangedEvent(request.items().stream()
                .map(OrderRequest.Item::productName)
                .collect(Collectors.toSet())));
        return OrderResponse.from(orderRepository.save(request.toEntity()));
    }

    public Page<OrderResponse> findAll(OrderStatus status, Pageable pageable) {
        Page<Order> orders = status == null
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);
        return orders.map(OrderResponse::from);
    }

    public OrderResponse findById(Long id) {
        return OrderResponse.from(getOrder(id));
    }

    /** save() 호출 없이 영속 상태 Entity를 변경하면 커밋 시 Dirty Checking으로 UPDATE된다. */
    @Transactional
    public OrderResponse changeStatus(Long id, OrderStatus status) {
        Order order = getOrder(id);
        order.changeStatus(status);
        if (status == OrderStatus.CANCELLED) {
            restoreStock(order);
        }
        return OrderResponse.from(order);
    }

    /** 아직 출고되지 않은(취소 가능한) 주문을 삭제하면 재고를 되돌린다. */
    @Transactional
    public void delete(Long id) {
        Order order = getOrder(id);
        if (order.isCancellable()) {
            restoreStock(order);
        }
        orderRepository.delete(order);
    }

    private Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    private void restoreStock(Order order) {
        order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getProductName))
                .forEach(item -> lockInventory(item.getProductName()).increase(item.getQuantity()));
        eventPublisher.publishEvent(new InventoryChangedEvent(order.getItems().stream()
                .map(OrderItem::getProductName)
                .collect(Collectors.toSet())));
    }

    private Inventory lockInventory(String productName) {
        return inventoryRepository.findByProductNameForUpdate(productName)
                .orElseThrow(() -> new UnknownProductException(productName));
    }
}
