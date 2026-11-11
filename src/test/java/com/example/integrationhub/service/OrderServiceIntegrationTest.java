package com.example.integrationhub.service;

import com.example.integrationhub.TestcontainersConfiguration;
import com.example.integrationhub.domain.Inventory;
import com.example.integrationhub.domain.Order;
import com.example.integrationhub.domain.OrderStatus;
import com.example.integrationhub.dto.OrderRequest;
import com.example.integrationhub.dto.OrderResponse;
import com.example.integrationhub.exception.InsufficientStockException;
import com.example.integrationhub.exception.InvalidOrderStatusException;
import com.example.integrationhub.exception.UnknownProductException;
import com.example.integrationhub.repository.InventoryRepository;
import com.example.integrationhub.repository.OrderRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.LazyInitializationException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 PostgreSQL(Testcontainers)에서 트랜잭션, 재고, 페이징, JPA 동작을 검증한다. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfiguration.class)
class OrderServiceIntegrationTest {

    @Autowired
    OrderService orderService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    InventoryRepository inventoryRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        inventoryRepository.saveAll(List.of(new Inventory("keyboard", 10), new Inventory("mouse", 5)));
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static OrderRequest.Item item(String productName, int quantity) {
        return new OrderRequest.Item(productName, new BigDecimal("1000"), quantity);
    }

    private static OrderRequest request(String customerId, OrderRequest.Item... items) {
        return new OrderRequest(customerId, List.of(items));
    }

    private int stock(String productName) {
        return inventoryRepository.findByProductName(productName).orElseThrow().getQuantity();
    }

    @Test
    void createPersistsOrderWithItemsAndDecreasesStock() {
        OrderResponse response = orderService.create(request("c1", item("keyboard", 2), item("mouse", 1)));

        // JPA를 거치지 않고 SQL로 실제 저장된 행을 확인한다.
        var row = jdbcTemplate.queryForMap("select customer_id, status from orders where id = ?", response.id());
        Integer itemRows = jdbcTemplate.queryForObject(
                "select count(*) from order_items where order_id = ?", Integer.class, response.id());

        assertThat(row).containsEntry("customer_id", "c1").containsEntry("status", "CREATED");
        assertThat(itemRows).isEqualTo(2);
        assertThat(response.totalAmount()).isEqualByComparingTo("3000");
        assertThat(stock("keyboard")).isEqualTo(8);
        assertThat(stock("mouse")).isEqualTo(4);
    }

    @Test
    void rollbackWhenStockIsInsufficient() {
        // 상품명 순으로 keyboard 차감이 먼저 성공한 뒤 mouse에서 실패한다.
        assertThatThrownBy(() -> orderService.create(request("c1", item("keyboard", 3), item("mouse", 6))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(stock("keyboard")).as("이미 차감한 재고도 Rollback").isEqualTo(10);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void rollbackWhenProductIsUnknown() {
        assertThatThrownBy(() -> orderService.create(request("c1", item("keyboard", 1), item("tablet", 1))))
                .isInstanceOf(UnknownProductException.class);

        assertThat(stock("keyboard")).isEqualTo(10);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void cancelRestoresStockOnlyOnce() {
        Long id = orderService.create(request("c1", item("keyboard", 4))).id();
        orderService.changeStatus(id, OrderStatus.PAID);

        orderService.changeStatus(id, OrderStatus.CANCELLED);

        assertThat(stock("keyboard")).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, id))
                .isEqualTo("CANCELLED");
        assertThatThrownBy(() -> orderService.changeStatus(id, OrderStatus.CANCELLED))
                .isInstanceOf(InvalidOrderStatusException.class);
        assertThat(stock("keyboard")).isEqualTo(10);
    }

    @Test
    void deleteRestoresStockOnlyForCancellableOrder() {
        Long created = orderService.create(request("c1", item("keyboard", 2))).id();
        Long shipped = orderService.create(request("c2", item("mouse", 1))).id();
        orderService.changeStatus(shipped, OrderStatus.PAID);
        orderService.changeStatus(shipped, OrderStatus.SHIPPED);

        orderService.delete(created);
        orderService.delete(shipped);

        assertThat(stock("keyboard")).isEqualTo(10);
        assertThat(stock("mouse")).as("출고된 주문은 재고를 되돌리지 않는다").isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_items", Integer.class)).isZero();
    }

    @Test
    void findAllSupportsPagingSortingAndStatusFilter() {
        List<Long> ids = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> orderService.create(request("c" + i, item("keyboard", 1))).id())
                .toList();
        orderService.changeStatus(ids.get(1), OrderStatus.PAID);
        orderService.changeStatus(ids.get(3), OrderStatus.PAID);

        var page = orderService.findAll(null, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "id")));
        var paid = orderService.findAll(OrderStatus.PAID, PageRequest.of(0, 10, Sort.by("id")));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).extracting(OrderResponse::id).containsExactly(ids.get(4), ids.get(3));
        assertThat(paid.getContent()).extracting(OrderResponse::id).containsExactly(ids.get(1), ids.get(3));
    }

    @Test
    void batchFetchAvoidsNPlusOne() {
        IntStream.range(0, 5).forEach(i -> orderService.create(request("c" + i, item("keyboard", 1))));
        statistics.clear();

        orderService.findAll(null, PageRequest.of(0, 10));

        // orders 1회 + order_items IN 조회 1회. Batch Fetch가 없으면 1 + 5(주문 수)회가 실행된다.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void persistenceContextReturnsSameInstanceWithinTransaction() {
        Long id = orderService.create(request("c1", item("keyboard", 1))).id();

        transactionTemplate.executeWithoutResult(status -> {
            statistics.clear();
            Order first = orderRepository.findById(id).orElseThrow();
            Order second = orderRepository.findById(id).orElseThrow();

            assertThat(second).isSameAs(first);                      // 1차 캐시: 동일성 보장
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        });
    }

    @Test
    void lazyLoadingOutsideTransactionFails() {
        Long id = orderService.create(request("c1", item("keyboard", 1))).id();

        // Repository 호출이 끝나면 트랜잭션과 영속성 컨텍스트가 닫혀 Entity는 준영속 상태가 된다.
        Order detached = orderRepository.findById(id).orElseThrow();

        assertThatThrownBy(() -> detached.getItems().size()).isInstanceOf(LazyInitializationException.class);
    }
}
