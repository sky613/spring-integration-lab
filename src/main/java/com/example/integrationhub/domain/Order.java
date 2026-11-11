package com.example.integrationhub.domain;

import com.example.integrationhub.exception.InvalidOrderStatusException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 주문 Entity (Aggregate Root). OrderItem의 생명주기를 함께 관리한다.
 * Setter 없이 생성자와 changeStatus()로만 상태를 바꾸고, 트랜잭션 안에서 변경하면 Dirty Checking으로 UPDATE된다.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String customerId;

    // ORDINAL(숫자)로 저장하면 Enum 순서가 바뀔 때 데이터가 깨지므로 이름으로 저장한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 낙관적 Lock: 동시에 같은 주문을 수정하면 나중에 커밋하는 쪽이 실패한다 (이중 취소로 인한 재고 이중 복구 방지).
    @Version
    private Long version;

    // 양방향 관계의 주인은 FK(order_id)를 가진 OrderItem.order다. 기본 Fetch 전략은 LAZY.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    /** JPA가 Proxy와 조회 결과 생성에 사용한다. 외부에서 빈 객체를 만들지 못하도록 protected. */
    protected Order() {
    }

    public Order(String customerId, List<OrderItem> items) {
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        // PostgreSQL timestamp 정밀도(마이크로초)에 맞춰 저장 전후 값이 같도록 한다.
        this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.status = OrderStatus.CREATED;
        items.forEach(this::addItem);
    }

    /** 연관관계 편의 메서드: 양쪽 참조를 함께 설정한다. */
    private void addItem(OrderItem item) {
        items.add(item);
        item.assignOrder(this);
    }

    public void changeStatus(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidOrderStatusException(id, status, next);
        }
        this.status = next;
    }

    public boolean isCancellable() {
        return status.canTransitionTo(OrderStatus.CANCELLED);
    }

    public BigDecimal totalAmount() {
        return items.stream()
                .map(OrderItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
