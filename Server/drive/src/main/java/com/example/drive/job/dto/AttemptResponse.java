package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.AttemptStatus;
import com.example.drive.job.domain.ExecutionAttempt;

public record AttemptResponse(
		UUID id,
		int attemptNumber,
		String workerId,
		AttemptStatus status,
		Instant createdAt,
		Instant startedAt,
		Instant endedAt,
		Long actualRuntimeMs,
		String failureReason
) {
	public static AttemptResponse from(ExecutionAttempt attempt) {
		return new AttemptResponse(
				attempt.getId(),
				attempt.getAttemptNumber(),
				attempt.getWorkerId(),
				attempt.getStatus(),
				attempt.getCreatedAt(),
				attempt.getStartedAt(),
				attempt.getEndedAt(),
				attempt.getActualRuntimeMs(),
				attempt.getFailureReason()
		);
	}
}
