package com.example.drive.job.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ArtifactCompletionDto(
		@NotBlank(message = "objectUri is required")
		String objectUri,

		@NotBlank(message = "contentType is required")
		String contentType,

		@NotNull(message = "sizeBytes is required")
		@Min(value = 0, message = "sizeBytes must be zero or positive")
		Long sizeBytes,

		@NotBlank(message = "checksum is required")
		@Pattern(regexp = "sha256:[0-9a-f]{64}", message = "checksum must be sha256 followed by 64 hex characters")
		String checksum
) {
}
