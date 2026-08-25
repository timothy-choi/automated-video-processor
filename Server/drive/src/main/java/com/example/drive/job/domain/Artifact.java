package com.example.drive.job.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "artifacts")
public class Artifact {

	@Id
	private UUID id;

	@Column(name = "job_id", nullable = false)
	private UUID jobId;

	@Column(name = "operation_id", nullable = false)
	private UUID operationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "artifact_type", nullable = false, length = 32)
	private ArtifactType type;

	@Column(name = "object_uri", nullable = false)
	private String objectUri;

	@Column(name = "content_type", nullable = false, length = 128)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(nullable = false, length = 128)
	private String checksum;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Artifact() {
	}

	public Artifact(
			UUID id,
			UUID jobId,
			UUID operationId,
			ArtifactType type,
			String objectUri,
			String contentType,
			long sizeBytes,
			String checksum,
			Instant createdAt
	) {
		this.id = id;
		this.jobId = jobId;
		this.operationId = operationId;
		this.type = type;
		this.objectUri = objectUri;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.checksum = checksum;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getJobId() {
		return jobId;
	}

	public UUID getOperationId() {
		return operationId;
	}

	public ArtifactType getType() {
		return type;
	}

	public String getObjectUri() {
		return objectUri;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}

	public String getChecksum() {
		return checksum;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
