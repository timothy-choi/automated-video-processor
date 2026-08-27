package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArtifactDownloadResponse(
		UUID artifactId,
		String url,
		Instant expiresAt,
		String contentType,
		String fileName
) {
}
