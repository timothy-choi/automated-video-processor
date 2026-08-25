package com.example.drive.job.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record FailOperationRequest(
		@Min(value = 0, message = "actualRuntimeMs must be zero or positive")
		Long actualRuntimeMs,

		@NotBlank(message = "reason is required")
		String reason
) {
}
