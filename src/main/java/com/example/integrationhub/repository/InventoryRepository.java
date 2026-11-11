package com.example.integrationhub.repository;

import com.example.integrationhub.domain.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductName(String productName);

    /** select ... for update: 트랜잭션이 끝날 때까지 다른 트랜잭션의 같은 재고 수정을 막는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productName = :productName")
    Optional<Inventory> findByProductNameForUpdate(String productName);
}
