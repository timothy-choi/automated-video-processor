package com.example.drive.job.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.domain.OperationType;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationResponse(
		UUID id,
		OperationType type,
		OperationStatus status,
		int order,
		Instant createdAt,
		Instant queuedAt,
		Instant updatedAt,
		Instant startedAt,
		Instant completedAt,
		Long actualRuntimeMs,
		String failureReason,
		Map<String, Object> result
) {
	public static OperationResponse from(Operation operation) {
		return new OperationResponse(
				operation.getId(),
				operation.getType(),
				operation.getStatus(),
				operation.getOperationOrder(),
				operation.getCreatedAt(),
				operation.getQueuedAt(),
				operation.getUpdatedAt(),
				operation.getStartedAt(),
				operation.getCompletedAt(),
				operation.getActualRuntimeMs(),
				operation.getFailureReason(),
				operation.getResultJson()
		);
	}
}
