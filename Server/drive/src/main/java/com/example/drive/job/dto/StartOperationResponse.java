package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.domain.OperationType;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartOperationResponse(
		StartOutcome outcome,
		UUID operationId,
		UUID jobId,
		OperationType type,
		String inputUri,
		OperationStatus status,
		UUID attemptId,
		String workerId,
		Instant leaseExpiresAt
) {
	public static StartOperationResponse from(StartOutcome outcome, Operation operation) {
		return new StartOperationResponse(
				outcome,
				operation.getId(),
				operation.getJob().getId(),
				operation.getType(),
				operation.getJob().getInputUri(),
				operation.getStatus(),
				null,
				null,
				null
		);
	}

	public static StartOperationResponse started(
			Operation operation,
			UUID attemptId,
			String workerId,
			Instant leaseExpiresAt
	) {
		return new StartOperationResponse(
				StartOutcome.STARTED,
				operation.getId(),
				operation.getJob().getId(),
				operation.getType(),
				operation.getJob().getInputUri(),
				operation.getStatus(),
				attemptId,
				workerId,
				leaseExpiresAt
		);
	}
}
