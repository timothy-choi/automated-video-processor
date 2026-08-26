package com.example.drive.dispatch;

import java.time.Instant;
import java.util.UUID;

/**
 * Assignment JSON envelopes. v1 is the legacy shared-queue contract.
 * v2 is the historical Phase 4A targeted contract without assignment identity.
 * v3 is the current targeted contract ({@code contracts/operation-assignment.v3.schema.json}).
 * {@code policy} is operation ordering (FIFO). {@code workerPolicy} is placement.
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

	public static String v2(
			UUID operationId,
			UUID jobId,
			String type,
			String inputUri,
			String workerId,
			Instant scheduledAt,
			String policy
	) {
		return "{\"schemaVersion\":2"
				+ ",\"operationId\":" + quote(operationId.toString())
				+ ",\"jobId\":" + quote(jobId.toString())
				+ ",\"type\":" + quote(type)
				+ ",\"inputUri\":" + quote(inputUri)
				+ ",\"workerId\":" + quote(workerId)
				+ ",\"scheduledAt\":" + quote(scheduledAt.toString())
				+ ",\"policy\":" + quote(policy)
				+ "}";
	}

	public static String v3(
			UUID operationId,
			UUID jobId,
			String type,
			String inputUri,
			String workerId,
			Instant scheduledAt,
			String policy,
			String workerPolicy,
			UUID assignmentId
	) {
		return "{\"schemaVersion\":3"
				+ ",\"operationId\":" + quote(operationId.toString())
				+ ",\"jobId\":" + quote(jobId.toString())
				+ ",\"type\":" + quote(type)
				+ ",\"inputUri\":" + quote(inputUri)
				+ ",\"workerId\":" + quote(workerId)
				+ ",\"scheduledAt\":" + quote(scheduledAt.toString())
				+ ",\"policy\":" + quote(policy)
				+ ",\"workerPolicy\":" + quote(workerPolicy)
				+ ",\"assignmentId\":" + quote(assignmentId.toString())
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
