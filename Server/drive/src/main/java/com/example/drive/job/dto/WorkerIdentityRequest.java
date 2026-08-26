package com.example.drive.job.dto;

import jakarta.validation.constraints.NotBlank;

public record WorkerIdentityRequest(
		@NotBlank(message = "workerId is required")
		String workerId
) {
}
