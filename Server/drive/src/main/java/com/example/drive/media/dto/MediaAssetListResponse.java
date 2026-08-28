package com.example.drive.media.dto;

import java.util.List;

public record MediaAssetListResponse(
		List<MediaAssetResponse> items,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
}
