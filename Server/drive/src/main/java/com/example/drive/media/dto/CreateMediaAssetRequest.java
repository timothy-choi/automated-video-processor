package com.example.drive.media.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateMediaAssetRequest(
		@NotBlank(message = "filename is required")
		@Size(max = 512, message = "filename must be at most 512 characters")
		String filename,

		String contentType,

		@NotNull(message = "sizeBytes is required")
		@Positive(message = "sizeBytes must be greater than 0")
		Long sizeBytes
) {
}
