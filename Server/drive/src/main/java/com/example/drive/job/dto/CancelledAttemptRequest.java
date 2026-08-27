package com.example.drive.job.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public record CancelledAttemptRequest(
		@NotBlank(message = "workerId is required")
		String workerId,
		Long actualRuntimeMs
) {
	public CancelledAttemptRequest {
		if (workerId != null) {
			workerId = workerId.trim();
		}
	}
}
