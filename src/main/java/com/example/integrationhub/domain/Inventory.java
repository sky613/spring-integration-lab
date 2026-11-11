package com.example.integrationhub.domain;

import com.example.integrationhub.exception.InsufficientStockException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 상품별 재고. 주문 생성 시 차감, 취소 시 복구한다. */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    protected Inventory() {
    }

    public Inventory(String productName, int quantity) {
        this.productName = productName;
        this.quantity = quantity;
    }

    public void decrease(int amount) {
        if (quantity < amount) {
            throw new InsufficientStockException(productName, amount, quantity);
        }
        quantity -= amount;
    }

    public void increase(int amount) {
        quantity += amount;
    }

    public Long getId() {
        return id;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }
}
