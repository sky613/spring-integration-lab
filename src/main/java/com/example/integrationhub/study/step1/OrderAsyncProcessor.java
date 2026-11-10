package com.example.integrationhub.study.step1;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * [CompletableFuture] 여러 주문의 결제 처리를 병렬로 실행하고 결과를 모은다.
 * Executor를 주입받아 Platform Thread Pool이나 Virtual Thread 중 무엇을 쓸지 호출자가 정한다.
 */
public class OrderAsyncProcessor {

    private final OrderService orderService;
    private final Executor executor;

    public OrderAsyncProcessor(OrderService orderService, Executor executor) {
        this.orderService = orderService;
        this.executor = executor;
    }

    /** 하나라도 실패하면 반환된 Future도 실패한다(fail-fast). 결과 순서는 입력 순서와 같다. */
    public CompletableFuture<List<OrderSummary>> payAll(List<Long> orderIds) {
        List<CompletableFuture<OrderSummary>> futures = orderIds.stream()
                .map(id -> CompletableFuture.supplyAsync(
                        () -> OrderSummary.from(orderService.changeStatus(id, OrderStatus.PAID)), executor))
                .toList();

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }
}
