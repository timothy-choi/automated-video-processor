package com.example.drive.scheduler.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssignOperationRequest(
		@NotNull(message = "operationId is required")
		UUID operationId,
		@NotBlank(message = "workerId is required")
		String workerId,
		@NotBlank(message = "policy is required")
		String policy
) {
}
