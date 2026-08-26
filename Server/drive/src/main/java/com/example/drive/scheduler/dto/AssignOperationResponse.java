package com.example.drive.scheduler.dto;

import java.time.Instant;
import java.util.UUID;

public record AssignOperationResponse(
		UUID decisionId,
		UUID operationId,
		UUID jobId,
		String workerId,
		String policy,
		String operationPolicy,
		String workerPolicy,
		Instant scheduledAt,
		String routingKey
) {
}
