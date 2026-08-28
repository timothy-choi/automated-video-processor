package com.example.drive.media;

import java.util.Locale;

final class MediaContentTypes {

	static final String OCTET_STREAM = "application/octet-stream";

	private MediaContentTypes() {
	}

	static String normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return OCTET_STREAM;
		}
		String value = raw.trim();
		if (value.length() > 255 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
			throw MediaAssetException.invalidContentType();
		}
		String lower = value.toLowerCase(Locale.ROOT);
		if (OCTET_STREAM.equals(lower) || lower.startsWith("video/") || lower.startsWith("audio/")) {
			return value;
		}
		throw MediaAssetException.invalidContentType();
	}
}
