package com.example.integrationhub.dto;

import com.example.integrationhub.domain.ExternalSystem;
import com.example.integrationhub.domain.IntegrationErrorType;
import com.example.integrationhub.domain.IntegrationHistory;
import com.example.integrationhub.domain.IntegrationStatus;

import java.time.Duration;
import java.time.LocalDateTime;

/** 연계 이력 응답. 실패한 경우에만 failedSystem, errorType, errorMessage가 채워진다. */
public record IntegrationHistoryResponse(
        Long id,
        Long orderId,
        IntegrationStatus status,
        String erpOrderNo,
        ExternalSystem failedSystem,
        IntegrationErrorType errorType,
        String errorMessage,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        long durationMs) {

    public static IntegrationHistoryResponse from(IntegrationHistory history) {
        return new IntegrationHistoryResponse(
                history.getId(),
                history.getOrderId(),
                history.getStatus(),
                history.getErpOrderNo(),
                history.getFailedSystem(),
                history.getErrorType(),
                history.getErrorMessage(),
                history.getRequestedAt(),
                history.getCompletedAt(),
                Duration.between(history.getRequestedAt(), history.getCompletedAt()).toMillis());
    }
}
