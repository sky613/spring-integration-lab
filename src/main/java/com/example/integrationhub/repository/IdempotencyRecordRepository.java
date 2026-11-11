package com.example.integrationhub.repository;

import com.example.integrationhub.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByOwnerAndIdempotencyKey(String owner, String idempotencyKey);
}
