package com.example.drive.job.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.JobPriority;
import com.example.drive.job.domain.JobStatus;
import com.example.drive.job.domain.Operation;

public record JobResponse(
		UUID id,
		String inputUri,
		JobStatus status,
		JobPriority priority,
		Instant deadline,
		Instant createdAt,
		Instant updatedAt,
		List<OperationResponse> operations
) {
	public static JobResponse from(Job job) {
		List<OperationResponse> operations = job.getOperations().stream()
				.sorted(Comparator.comparingInt(Operation::getOperationOrder))
				.map(OperationResponse::from)
				.toList();
		return new JobResponse(
				job.getId(),
				job.getInputUri(),
				job.getStatus(),
				job.getPriority(),
				job.getDeadline(),
				job.getCreatedAt(),
				job.getUpdatedAt(),
				operations
		);
	}
}
