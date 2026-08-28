package com.example.drive.job.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.JobPriority;
import com.example.drive.job.domain.JobStatus;
import com.example.drive.job.domain.Operation;
import com.fasterxml.jackson.annotation.JsonInclude;

public record JobResponse(
		UUID id,
		String inputUri,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		UUID mediaAssetId,
		JobStatus status,
		JobPriority priority,
		Instant deadline,
		Instant createdAt,
		Instant updatedAt,
		long operationCount,
		long artifactCount,
		List<OperationResponse> operations
) {
	public static JobResponse from(Job job) {
		return from(job, 0L);
	}

	public static JobResponse from(Job job, long artifactCount) {
		List<OperationResponse> operations = job.getOperations().stream()
				.sorted(Comparator.comparingInt(Operation::getOperationOrder))
				.map(OperationResponse::from)
				.toList();
		return new JobResponse(
				job.getId(),
				job.getInputUri(),
				job.getMediaAssetId(),
				job.getStatus(),
				job.getPriority(),
				job.getDeadline(),
				job.getCreatedAt(),
				job.getUpdatedAt(),
				operations.size(),
				artifactCount,
				operations
		);
	}
}
