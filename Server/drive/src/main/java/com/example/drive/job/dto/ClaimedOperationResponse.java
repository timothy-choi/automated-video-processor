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
		OperationStatus status,
		UUID attemptId,
		String workerId,
		Instant leaseExpiresAt
) {
	public static ClaimedOperationResponse from(
			Operation operation,
			Instant claimedAt,
			UUID attemptId,
			String workerId,
			Instant leaseExpiresAt
	) {
		return new ClaimedOperationResponse(
				operation.getId(),
				operation.getJob().getId(),
				operation.getType(),
				operation.getJob().getInputUri(),
				claimedAt,
				operation.getStatus(),
				attemptId,
				workerId,
				leaseExpiresAt
		);
	}
}
