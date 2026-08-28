package com.example.drive.media.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.media.MediaAssetStatus;
import com.example.drive.media.domain.MediaAsset;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaAssetResponse(
		UUID id,
		MediaAssetStatus status,
		String filename,
		String contentType,
		Long sizeBytes,
		String objectUri,
		Instant createdAt,
		Instant updatedAt
) {
	public static MediaAssetResponse from(MediaAsset asset) {
		return from(asset, true);
	}

	public static MediaAssetResponse summary(MediaAsset asset) {
		return from(asset, false);
	}

	private static MediaAssetResponse from(MediaAsset asset, boolean includeObjectUri) {
		return new MediaAssetResponse(
				asset.getId(),
				asset.getStatus(),
				asset.getOriginalFilename(),
				asset.getContentType(),
				asset.getSizeBytes(),
				includeObjectUri ? asset.canonicalObjectUri() : null,
				asset.getCreatedAt(),
				asset.getUpdatedAt()
		);
	}
}
