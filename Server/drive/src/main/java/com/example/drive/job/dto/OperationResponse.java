package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.domain.OperationType;

public record OperationResponse(
		UUID id,
		OperationType type,
		OperationStatus status,
		int order,
		Instant createdAt,
		Instant updatedAt
) {
	public static OperationResponse from(Operation operation) {
		return new OperationResponse(
				operation.getId(),
				operation.getType(),
				operation.getStatus(),
				operation.getOperationOrder(),
				operation.getCreatedAt(),
				operation.getUpdatedAt()
		);
	}
}
