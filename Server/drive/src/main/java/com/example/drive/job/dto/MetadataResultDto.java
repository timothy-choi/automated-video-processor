package com.example.drive.job.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetadataResultDto(
		Double durationSeconds,
		String formatName,
		Long sizeBytes,
		String videoCodec,
		String audioCodec,
		Integer width,
		Integer height,
		Double frameRate
) {
}
