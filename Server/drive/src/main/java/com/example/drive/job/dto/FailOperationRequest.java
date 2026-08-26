package com.example.drive.job.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FailOperationRequest(
		@NotNull(message = "attemptId is required")
		UUID attemptId,

		@Min(value = 0, message = "actualRuntimeMs must be zero or positive")
		Long actualRuntimeMs,

		@NotBlank(message = "reason is required")
		String reason
) {
}
