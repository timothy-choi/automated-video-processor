package com.example.drive.job.dto;

import java.util.List;
import java.util.UUID;

import com.example.drive.job.domain.Artifact;

public record JobArtifactsResponse(
		UUID jobId,
		List<ArtifactResponse> artifacts
) {
	public static JobArtifactsResponse from(UUID jobId, List<Artifact> artifacts) {
		return new JobArtifactsResponse(
				jobId,
				artifacts.stream().map(ArtifactResponse::from).toList()
		);
	}
}
