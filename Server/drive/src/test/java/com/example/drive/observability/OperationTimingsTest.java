package com.example.drive.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class OperationTimingsTest {

	@Test
	void queueWaitIsAssignedAtMinusQueuedAt() {
		Instant queued = Instant.parse("2026-08-27T21:00:00Z");
		Instant assigned = Instant.parse("2026-08-27T21:00:02.500Z");
		assertThat(OperationTimings.queueWait(queued, assigned)).contains(Duration.ofMillis(2500));
	}

	@Test
	void assignmentWaitIsStartedAtMinusAssignedAt() {
		Instant assigned = Instant.parse("2026-08-27T21:00:02Z");
		Instant started = Instant.parse("2026-08-27T21:00:03Z");
		assertThat(OperationTimings.assignmentWait(assigned, started)).contains(Duration.ofSeconds(1));
	}

	@Test
	void missingOrInvertedTimestampsAreEmpty() {
		Instant now = Instant.parse("2026-08-27T21:00:00Z");
		assertThat(OperationTimings.queueWait(null, now)).isEmpty();
		assertThat(OperationTimings.assignmentWait(now, now.minusSeconds(1))).isEmpty();
		assertThat(OperationTimings.jobE2e(now, now.plusSeconds(9))).contains(Duration.ofSeconds(9));
	}
}
