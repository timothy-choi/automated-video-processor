package com.example.drive.job.dto;

import com.example.drive.job.domain.OperationType;

import jakarta.validation.constraints.NotNull;

public record CreateOperationRequest(
		@NotNull(message = "operation type is required")
		OperationType type
) {
}
