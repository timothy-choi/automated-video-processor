package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.domain.OperationType;

public record ClaimedOperationResponse(
		UUID operationId,
		UUID jobId,
		OperationType type,
		String inputUri,
		Instant claimedAt,
		OperationStatus status
) {
	public static ClaimedOperationResponse from(Operation operation, Instant claimedAt) {
		return new ClaimedOperationResponse(
				operation.getId(),
				operation.getJob().getId(),
				operation.getType(),
				operation.getJob().getInputUri(),
				claimedAt,
				operation.getStatus()
		);
	}
}
