package com.example.integrationhub.study.step1;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordTest {

    @Test
    void recordsWithSameValuesAreEqual() {
        var a = new OrderItem("keyboard", new BigDecimal("50000"), 2);
        var b = new OrderItem("keyboard", new BigDecimal("50000"), 2);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.amount()).isEqualByComparingTo("100000");
        assertThat(a.toString()).contains("productName=keyboard");
    }

    @Test
    void compactConstructorValidatesInput() {
        assertThatThrownBy(() -> new OrderItem("keyboard", BigDecimal.TEN, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderItem("keyboard", new BigDecimal("-1"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreateOrderRequest("c1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestCopiesItemsDefensively() {
        var items = new ArrayList<OrderItem>();
        items.add(new OrderItem("mouse", BigDecimal.TEN, 1));
        var request = new CreateOrderRequest("c1", items);

        items.clear();

        assertThat(request.items()).hasSize(1);
        assertThatThrownBy(() -> request.items().add(new OrderItem("pad", BigDecimal.ONE, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
