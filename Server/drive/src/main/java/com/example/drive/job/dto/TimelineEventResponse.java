package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.ArtifactType;
import com.example.drive.job.domain.AttemptStatus;
import com.example.drive.job.domain.OperationType;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineEventResponse(
		Instant timestamp,
		TimelineEventType type,
		String message,
		UUID operationId,
		OperationType operationType,
		Integer operationOrder,
		UUID attemptId,
		Integer attemptNumber,
		AttemptStatus outcome,
		String workerId,
		String operationPolicy,
		String workerPolicy,
		UUID artifactId,
		ArtifactType artifactType,
		Long sizeBytes,
		String checksum,
		Long runtimeMs,
		String failureReason
) {
}
