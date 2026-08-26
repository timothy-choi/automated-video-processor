package com.example.drive.job.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public record WorkerIdentityRequest(
		@NotBlank(message = "workerId is required")
		String workerId,
		UUID assignmentId
) {
}
