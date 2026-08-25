package com.example.drive.job.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CompleteOperationRequest(
		@NotNull(message = "actualRuntimeMs is required")
		@Min(value = 0, message = "actualRuntimeMs must be zero or positive")
		Long actualRuntimeMs,

		@Valid
		MetadataResultDto metadata,

		@Valid
		ArtifactCompletionDto artifact
) {
}
