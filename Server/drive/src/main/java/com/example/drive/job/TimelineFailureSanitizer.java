package com.example.drive.job;

import java.util.regex.Pattern;

import com.example.drive.observability.TelemetryRedaction;

/**
 * Public-API sanitizer for persisted failure text.
 *
 * <p>Uses {@link TelemetryRedaction} for credentials and signed URLs, then strips
 * stack-trace frames and bounds length so the timeline never returns Java/Go stacks,
 * tokens, or internal transport details.
 */
final class TimelineFailureSanitizer {

	private static final int MAX_LENGTH = 500;
	private static final Pattern STACK_FRAME = Pattern.compile("(?m)^\\s*(at |Caused by: |\\t+at ).*$");
	private static final Pattern CONNECTION_STRING = Pattern.compile(
			"(?i)(amqp|postgres(?:ql)?|jdbc:[a-z0-9]+|mongodb|redis|https?)://[^\\s]+"
	);
	private static final Pattern EXCEPTION_PREFIX = Pattern.compile(
			"(?i)^(java\\.|javax\\.|jakarta\\.|org\\.springframework\\.|org\\.hibernate\\.|com\\.example\\.drive\\.|runtime error:).*"
	);

	private TimelineFailureSanitizer() {
	}

	static String sanitize(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String text = firstLine(raw.strip());
		text = STACK_FRAME.matcher(text).replaceAll("").strip();
		if (TelemetryRedaction.looksLikeSecret(text) || looksLikeConnectionString(text)) {
			return "Operation failed";
		}
		text = CONNECTION_STRING.matcher(text).replaceAll("[redacted]");
		if (EXCEPTION_PREFIX.matcher(text).matches() && text.contains(": ")) {
			text = text.substring(text.indexOf(": ") + 2).strip();
		}
		if (text.isBlank() || "[redacted]".equals(text)) {
			return "Operation failed";
		}
		if (text.length() > MAX_LENGTH) {
			return text.substring(0, MAX_LENGTH);
		}
		return text;
	}

	private static boolean looksLikeConnectionString(String text) {
		String lower = text.toLowerCase();
		return lower.contains("amqp://")
				|| lower.contains("postgresql://")
				|| lower.contains("postgres://")
				|| lower.contains("jdbc:")
				|| lower.contains("secret_access_key")
				|| lower.contains("minioadmin")
				|| lower.contains("worker_pepper")
				|| lower.contains("scheduler_token")
				|| lower.contains("mp_live_")
				|| lower.contains("mp_wk_");
	}

	private static String firstLine(String text) {
		int newline = indexOfNewline(text);
		if (newline < 0) {
			return text;
		}
		return text.substring(0, newline).strip();
	}

	private static int indexOfNewline(String text) {
		int n = text.indexOf('\n');
		int r = text.indexOf('\r');
		if (n < 0) {
			return r;
		}
		if (r < 0) {
			return n;
		}
		return Math.min(n, r);
	}
}
