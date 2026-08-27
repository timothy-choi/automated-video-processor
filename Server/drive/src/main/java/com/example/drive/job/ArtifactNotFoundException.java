package com.example.drive.job;

import java.util.UUID;

public class ArtifactNotFoundException extends RuntimeException {

	private final UUID artifactId;

	public ArtifactNotFoundException(UUID artifactId) {
		super("Artifact " + artifactId + " was not found");
		this.artifactId = artifactId;
	}

	public UUID getArtifactId() {
		return artifactId;
	}
}
