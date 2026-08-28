package com.example.drive.media.dto;

import java.time.Instant;
import java.util.Map;

public record UploadUrlResponse(
		String method,
		String url,
		Instant expiresAt,
		Map<String, String> headers
) {
	public static UploadUrlResponse put(String url, Instant expiresAt, String contentType) {
		return new UploadUrlResponse("PUT", url, expiresAt, Map.of("Content-Type", contentType));
	}
}
