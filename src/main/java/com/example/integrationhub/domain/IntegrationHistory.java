package com.example.integrationhub.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 주문 1건에 대한 ERP/MES 연계 시도 1회의 결과. 실패하면 실패 시스템, 오류 유형, 메시지(실패 이벤트)를 함께 저장한다.
 * 외부 호출이 모두 끝난 뒤 한 번에 INSERT하므로 상태를 바꾸는 메서드가 없다.
 */
@Entity
@Table(name = "integration_history")
public class IntegrationHistory {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IntegrationStatus status;

    @Column(length = 50)
    private String erpOrderNo;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ExternalSystem failedSystem;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private IntegrationErrorType errorType;

    @Column(length = MAX_ERROR_MESSAGE_LENGTH)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    @Column(nullable = false)
    private LocalDateTime completedAt;

    protected IntegrationHistory() {
    }

    private IntegrationHistory(Long orderId, IntegrationStatus status, String erpOrderNo, ExternalSystem failedSystem,
                               IntegrationErrorType errorType, String errorMessage, LocalDateTime requestedAt) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = status;
        this.erpOrderNo = erpOrderNo;
        this.failedSystem = failedSystem;
        this.errorType = errorType;
        this.errorMessage = truncate(errorMessage);
        this.requestedAt = requestedAt.truncatedTo(ChronoUnit.MICROS);
        this.completedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static IntegrationHistory success(Long orderId, String erpOrderNo, LocalDateTime requestedAt) {
        return new IntegrationHistory(orderId, IntegrationStatus.SUCCESS, erpOrderNo, null, null, null, requestedAt);
    }

    /** erpOrderNo: ERP 전송은 성공하고 MES에서 실패한 경우 ERP가 이미 받은 주문 번호를 남긴다. */
    public static IntegrationHistory failure(Long orderId, String erpOrderNo, ExternalSystem failedSystem,
                                             IntegrationErrorType errorType, String errorMessage,
                                             LocalDateTime requestedAt) {
        return new IntegrationHistory(orderId, IntegrationStatus.FAILED, erpOrderNo,
                Objects.requireNonNull(failedSystem, "failedSystem"),
                Objects.requireNonNull(errorType, "errorType"), errorMessage, requestedAt);
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public IntegrationStatus getStatus() {
        return status;
    }

    public String getErpOrderNo() {
        return erpOrderNo;
    }

    public ExternalSystem getFailedSystem() {
        return failedSystem;
    }

    public IntegrationErrorType getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
}
