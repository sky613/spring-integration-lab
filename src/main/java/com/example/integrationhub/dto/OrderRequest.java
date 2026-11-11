package com.example.integrationhub.dto;

import com.example.integrationhub.domain.Order;
import com.example.integrationhub.domain.OrderItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** 주문 생성 요청 DTO. Bean Validation으로 Controller 진입 시점에 형식을 검증한다. */
public record OrderRequest(
        @NotBlank @Size(max = 100) String customerId,
        @NotEmpty @Valid List<Item> items) {

    public record Item(
            @NotBlank @Size(max = 100) String productName,
            @NotNull @PositiveOrZero BigDecimal unitPrice,
            @Positive int quantity) {
    }

    public Order toEntity() {
        return new Order(customerId, items.stream()
                .map(item -> new OrderItem(item.productName(), item.unitPrice(), item.quantity()))
                .toList());
    }
}
