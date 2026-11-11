package com.example.integrationhub.dto;

import com.example.integrationhub.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record OrderStatusRequest(@NotNull OrderStatus status) {
}
