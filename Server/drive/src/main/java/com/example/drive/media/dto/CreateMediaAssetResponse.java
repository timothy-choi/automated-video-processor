package com.example.drive.media.dto;

public record CreateMediaAssetResponse(
		MediaAssetResponse mediaAsset,
		UploadUrlResponse upload
) {
}
