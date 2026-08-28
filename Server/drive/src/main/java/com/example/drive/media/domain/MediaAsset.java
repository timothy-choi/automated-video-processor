package com.example.drive.media.domain;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.media.MediaAssetStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "media_assets")
public class MediaAsset {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private MediaAssetStatus status;

	@Column(name = "original_filename", nullable = false, length = 255)
	private String originalFilename;

	@Column(name = "content_type", length = 255)
	private String contentType;

	@Column(name = "size_bytes")
	private Long sizeBytes;

	@Column(nullable = false, length = 255)
	private String bucket;

	@Column(name = "object_key", nullable = false, length = 1024)
	private String objectKey;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected MediaAsset() {
	}

	public MediaAsset(
			UUID id,
			UUID accountId,
			String originalFilename,
			String contentType,
			Long sizeBytes,
			String bucket,
			String objectKey,
			Instant now
	) {
		this.id = id;
		this.accountId = accountId;
		this.status = MediaAssetStatus.PENDING_UPLOAD;
		this.originalFilename = originalFilename;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.bucket = bucket;
		this.objectKey = objectKey;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public void markReady(long actualSizeBytes, String actualContentType, Instant now) {
		this.status = MediaAssetStatus.READY;
		this.sizeBytes = actualSizeBytes;
		if (actualContentType != null && !actualContentType.isBlank()) {
			this.contentType = actualContentType;
		}
		this.updatedAt = now;
	}

	public void markFailed(Long actualSizeBytes, Instant now) {
		this.status = MediaAssetStatus.FAILED;
		if (actualSizeBytes != null) {
			this.sizeBytes = actualSizeBytes;
		}
		this.updatedAt = now;
	}

	public String canonicalObjectUri() {
		return "s3://" + bucket + "/" + objectKey;
	}

	public UUID getId() {
		return id;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public MediaAssetStatus getStatus() {
		return status;
	}

	public String getOriginalFilename() {
		return originalFilename;
	}

	public String getContentType() {
		return contentType;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public String getBucket() {
		return bucket;
	}

	public String getObjectKey() {
		return objectKey;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
