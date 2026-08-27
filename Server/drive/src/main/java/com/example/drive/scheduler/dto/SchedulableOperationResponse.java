package com.example.drive.scheduler.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationType;

public record SchedulableOperationResponse(
		UUID operationId,
		UUID jobId,
		OperationType type,
		String inputUri,
		Instant createdAt,
		Instant queuedAt,
		int operationOrder
) {
	public static SchedulableOperationResponse from(Operation operation) {
		return new SchedulableOperationResponse(
				operation.getId(),
				operation.getJob().getId(),
				operation.getType(),
				operation.getJob().getInputUri(),
				operation.getCreatedAt(),
				operation.getQueuedAt(),
				operation.getOperationOrder()
		);
	}
}
