package com.example.drive.scheduler.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssignOperationRequest(
		@NotNull(message = "operationId is required")
		UUID operationId,
		@NotBlank(message = "workerId is required")
		String workerId,
		String operationPolicy,
		String workerPolicy,
		String policy
) {
	public AssignOperationRequest(UUID operationId, String workerId, String operationPolicy, String workerPolicy) {
		this(operationId, workerId, operationPolicy, workerPolicy, null);
	}
}
