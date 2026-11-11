package com.example.integrationhub.repository;

import com.example.integrationhub.domain.IntegrationHistory;
import com.example.integrationhub.domain.IntegrationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationHistoryRepository extends JpaRepository<IntegrationHistory, Long> {

    /** null인 조건은 무시한다. 예: orderId만 주면 해당 주문의 전체 이력. */
    @Query("""
            select h from IntegrationHistory h
            where (:orderId is null or h.orderId = :orderId)
              and (:status is null or h.status = :status)
            """)
    Page<IntegrationHistory> search(@Param("orderId") Long orderId,
                                    @Param("status") IntegrationStatus status,
                                    Pageable pageable);
}
