package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.JobPriority;
import com.example.drive.job.domain.JobStatus;

public record JobSummaryResponse(
		UUID id,
		String inputUri,
		JobStatus status,
		JobPriority priority,
		Instant deadline,
		Instant createdAt,
		Instant updatedAt,
		long operationCount,
		long artifactCount
) {
	public static JobSummaryResponse from(Job job, long operationCount, long artifactCount) {
		return new JobSummaryResponse(
				job.getId(),
				job.getInputUri(),
				job.getStatus(),
				job.getPriority(),
				job.getDeadline(),
				job.getCreatedAt(),
				job.getUpdatedAt(),
				operationCount,
				artifactCount
		);
	}
}
