package com.example.drive.job.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.drive.job.domain.JobStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobTimelineResponse(
		UUID jobId,
		JobStatus status,
		Instant createdAt,
		Instant updatedAt,
		Instant completedAt,
		Long durationMs,
		int operationCount,
		int completedOperationCount,
		int failedOperationCount,
		int cancelledOperationCount,
		int artifactCount,
		String traceId,
		List<TimelineEventResponse> events,
		List<OperationTimingResponse> operations
) {
}
