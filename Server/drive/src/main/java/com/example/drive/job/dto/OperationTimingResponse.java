package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.domain.OperationType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-operation latency summary derived from persisted timestamps.
 *
 * <p>Null means the duration cannot be computed from stored fields. Missing
 * timestamps are never replaced with zero. Negative diffs are omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationTimingResponse(
		UUID operationId,
		OperationType type,
		int order,
		OperationStatus status,
		Instant queuedAt,
		Instant assignedAt,
		Instant startedAt,
		Instant completedAt,
		Long queueWaitMs,
		Long assignmentWaitMs,
		Long executionRuntimeMs,
		Long totalOperationLatencyMs,
		Integer attemptCount,
		String lastWorkerId
) {
}
