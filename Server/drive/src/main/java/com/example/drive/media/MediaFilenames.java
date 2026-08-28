package com.example.drive.media;

final class MediaFilenames {

	static final String FALLBACK = "upload.bin";
	static final int MAX_LENGTH = 255;

	private MediaFilenames() {
	}

	/**
	 * Display-only sanitization. The object key never uses this value.
	 * Path separators, control characters, and {@code ..} are stripped.
	 */
	static String sanitize(String raw) {
		if (raw == null || raw.isBlank()) {
			return FALLBACK;
		}
		String name = raw.replace('\\', '/');
		int slash = name.lastIndexOf('/');
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		StringBuilder cleaned = new StringBuilder(Math.min(name.length(), MAX_LENGTH));
		for (int i = 0; i < name.length() && cleaned.length() < MAX_LENGTH; i++) {
			char c = name.charAt(i);
			if (c < 32 || c == 127) {
				continue;
			}
			cleaned.append(c);
		}
		name = cleaned.toString().trim();
		if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
			return FALLBACK;
		}
		return name;
	}
}
