package com.example.drive.job.dto;

import java.util.UUID;

import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.domain.OperationType;

public record StartOperationResponse(
		StartOutcome outcome,
		UUID operationId,
		UUID jobId,
		OperationType type,
		String inputUri,
		OperationStatus status
) {
	public static StartOperationResponse from(StartOutcome outcome, Operation operation) {
		return new StartOperationResponse(
				outcome,
				operation.getId(),
				operation.getJob().getId(),
				operation.getType(),
				operation.getJob().getInputUri(),
				operation.getStatus()
		);
	}
}
