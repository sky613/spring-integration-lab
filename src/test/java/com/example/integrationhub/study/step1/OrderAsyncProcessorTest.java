package com.example.integrationhub.study.step1;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static com.example.integrationhub.study.step1.OrderFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderAsyncProcessorTest {

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(new InMemoryOrderRepository());
        for (int i = 0; i < 100; i++) {
            service.create(request("c" + (i % 3), 1000));
        }
    }

    @Test
    void payAllWithVirtualThreads() {
        var ids = service.findAll().stream().map(Order::getId).toList();

        // Java 21: ExecutorService는 AutoCloseable이므로 try-with-resources로 종료를 보장한다.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new OrderAsyncProcessor(service, executor).payAll(ids).join();

            assertThat(result).extracting(OrderSummary::orderId).containsExactlyElementsOf(ids);
            assertThat(result).allMatch(summary -> summary.status() == OrderStatus.PAID);
        }
    }

    @Test
    void payAllFailsWhenAnyOrderIsMissing() {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var future = new OrderAsyncProcessor(service, executor).payAll(List.of(1L, 999L));

            assertThatThrownBy(future::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(OrderNotFoundException.class);
        }
    }

    @Test
    void exceptionallyRecoversFromFailure() {
        Optional<OrderSummary> recovered = CompletableFuture
                .supplyAsync(() -> Optional.of(OrderSummary.from(service.getById(999L))))
                .exceptionally(ex -> Optional.empty())
                .join();

        assertThat(recovered).isEmpty();
    }

    @Test
    void concurrentStatusChangeAllowsOnlyOneWinner() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 50)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            service.changeStatus(1L, OrderStatus.PAID);
                            return true;
                        } catch (InvalidOrderStatusException e) {
                            return false;
                        }
                    }, executor))
                    .toList();

            long winners = futures.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count();

            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void threadBasics() throws InterruptedException {
        var ran = new AtomicBoolean(false);

        Thread platform = new Thread(() -> ran.set(true));
        platform.start();
        platform.join();
        assertThat(ran).isTrue();

        Thread virtual = Thread.ofVirtual().start(() -> { });
        assertThat(virtual.isVirtual()).isTrue();
        assertThat(virtual.join(Duration.ofSeconds(1))).isTrue();
    }
}
