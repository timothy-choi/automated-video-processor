package com.example.drive.dispatch;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical v1 assignment JSON. Keep aligned with
 * {@code contracts/operation-assignment.v1.schema.json}.
 */
public final class AssignmentJson {

	private AssignmentJson() {
	}

	public static String v1(UUID operationId, UUID jobId, String type, String inputUri, Instant dispatchedAt) {
		return "{\"schemaVersion\":1"
				+ ",\"operationId\":" + quote(operationId.toString())
				+ ",\"jobId\":" + quote(jobId.toString())
				+ ",\"type\":" + quote(type)
				+ ",\"inputUri\":" + quote(inputUri)
				+ ",\"dispatchedAt\":" + quote(dispatchedAt.toString())
				+ "}";
	}

	static String quote(String value) {
		StringBuilder builder = new StringBuilder(value.length() + 2);
		builder.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> builder.append("\\\"");
				case '\\' -> builder.append("\\\\");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				default -> {
					if (c < 0x20) {
						builder.append(String.format("\\u%04x", (int) c));
					}
					else {
						builder.append(c);
					}
				}
			}
		}
		builder.append('"');
		return builder.toString();
	}
}
