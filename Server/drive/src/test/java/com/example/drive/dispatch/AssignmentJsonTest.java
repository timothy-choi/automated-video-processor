package com.example.drive.dispatch;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentJsonTest {

	@Test
	void writesVersionedEnvelopeWithoutJpaFields() {
		UUID operationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID jobId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		Instant dispatchedAt = Instant.parse("2026-08-25T02:00:00Z");

		String json = AssignmentJson.v1(operationId, jobId, "METADATA", "s3://media-input/sample.mp4", dispatchedAt);

		assertThat(json).isEqualTo(
				"{\"schemaVersion\":1,\"operationId\":\"11111111-1111-1111-1111-111111111111\",\"jobId\":\"22222222-2222-2222-2222-222222222222\",\"type\":\"METADATA\",\"inputUri\":\"s3://media-input/sample.mp4\",\"dispatchedAt\":\"2026-08-25T02:00:00Z\"}"
		);
		assertThat(json).doesNotContain("hibernate").doesNotContain("password");
	}

	@Test
	void escapesQuotesInInputUri() {
		String json = AssignmentJson.v1(
				UUID.fromString("11111111-1111-1111-1111-111111111111"),
				UUID.fromString("22222222-2222-2222-2222-222222222222"),
				"THUMBNAIL",
				"s3://media-input/weird\"name.mp4",
				Instant.parse("2026-08-25T02:00:00Z")
		);
		assertThat(json).contains("\"inputUri\":\"s3://media-input/weird\\\"name.mp4\"");
	}

	@Test
	void writesVersionedV2EnvelopeWithWorkerAndPolicy() {
		UUID operationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID jobId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		Instant scheduledAt = Instant.parse("2026-08-25T18:00:00Z");

		String json = AssignmentJson.v2(
				operationId,
				jobId,
				"THUMBNAIL",
				"s3://media-input/sample.mp4",
				"worker-a",
				scheduledAt,
				"FIFO"
		);

		assertThat(json).isEqualTo(
				"{\"schemaVersion\":2,\"operationId\":\"11111111-1111-1111-1111-111111111111\",\"jobId\":\"22222222-2222-2222-2222-222222222222\",\"type\":\"THUMBNAIL\",\"inputUri\":\"s3://media-input/sample.mp4\",\"workerId\":\"worker-a\",\"scheduledAt\":\"2026-08-25T18:00:00Z\",\"policy\":\"FIFO\"}"
		);
		assertThat(json).doesNotContain("attemptId").doesNotContain("hibernate");
	}

	@Test
	void writesVersionedV3EnvelopeWithAssignmentId() {
		UUID operationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID jobId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		UUID assignmentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		Instant scheduledAt = Instant.parse("2026-08-25T18:00:00Z");

		String json = AssignmentJson.v3(
				operationId,
				jobId,
				"THUMBNAIL",
				"s3://media-input/sample.mp4",
				"worker-a",
				scheduledAt,
				"FIFO",
				assignmentId
		);

		assertThat(json).isEqualTo(
				"{\"schemaVersion\":3,\"operationId\":\"11111111-1111-1111-1111-111111111111\",\"jobId\":\"22222222-2222-2222-2222-222222222222\",\"type\":\"THUMBNAIL\",\"inputUri\":\"s3://media-input/sample.mp4\",\"workerId\":\"worker-a\",\"scheduledAt\":\"2026-08-25T18:00:00Z\",\"policy\":\"FIFO\",\"assignmentId\":\"33333333-3333-3333-3333-333333333333\"}"
		);
		assertThat(json).doesNotContain("attemptId").doesNotContain("hibernate");
	}
}
