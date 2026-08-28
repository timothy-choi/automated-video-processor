package com.example.drive.observability;

import java.util.Locale;
import java.util.Set;

public final class TelemetryRedaction {

	private static final Set<String> SENSITIVE_KEYS = Set.of(
			"authorization",
			"api_key",
			"api-key",
			"scheduler_token",
			"worker_token",
			"worker_pepper",
			"secret_access_key",
			"access_key",
			"signed_url",
			"presigned_url",
			"password",
			"token"
	);

	private TelemetryRedaction() {
	}

	public static boolean isSensitiveKey(String key) {
		if (key == null || key.isBlank()) {
			return false;
		}
		String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
		if (SENSITIVE_KEYS.contains(normalized)) {
			return true;
		}
		return normalized.contains("token")
				|| normalized.contains("password")
				|| normalized.contains("secret")
				|| normalized.contains("authorization");
	}

	public static boolean looksLikeSecret(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.startsWith("mp_live_") || lower.startsWith("mp_wk_") || lower.startsWith("bearer ")) {
			return true;
		}
		return lower.contains("x-amz-signature")
				|| lower.contains("x-amz-credential")
				|| lower.contains("x-amz-security-token")
				|| lower.contains("awsaccesskeyid")
				|| lower.contains("authorization=");
	}

	public static String boundedMessage(String message) {
		if (message == null) {
			return "";
		}
		String trimmed = message.trim();
		if (looksLikeSecret(trimmed)) {
			return "redacted";
		}
		if (trimmed.length() <= 256) {
			return trimmed;
		}
		return trimmed.substring(0, 256);
	}
}
