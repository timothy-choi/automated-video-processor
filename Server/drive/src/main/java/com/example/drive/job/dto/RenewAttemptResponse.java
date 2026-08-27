package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.AttemptStatus;

public record RenewAttemptResponse(
		UUID attemptId,
		String workerId,
		AttemptStatus status,
		Instant leaseExpiresAt,
		boolean cancelRequested
) {
}
