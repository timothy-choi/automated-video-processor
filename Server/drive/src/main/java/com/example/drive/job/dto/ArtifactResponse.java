package com.example.drive.job.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.job.domain.Artifact;
import com.example.drive.job.domain.ArtifactType;

public record ArtifactResponse(
		UUID id,
		UUID operationId,
		ArtifactType type,
		String objectUri,
		String contentType,
		long sizeBytes,
		String checksum,
		Instant createdAt
) {
	public static ArtifactResponse from(Artifact artifact) {
		return new ArtifactResponse(
				artifact.getId(),
				artifact.getOperationId(),
				artifact.getType(),
				artifact.getObjectUri(),
				artifact.getContentType(),
				artifact.getSizeBytes(),
				artifact.getChecksum(),
				artifact.getCreatedAt()
		);
	}
}
