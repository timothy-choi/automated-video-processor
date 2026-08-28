package com.example.drive.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Wait definitions from persisted Operation timestamps.
 *
 * <p>Queue wait is {@code assignedAt - queuedAt}. {@code queuedAt} is set when the
 * operation is created and reset on retry or requeue. {@code assignedAt} is the
 * time the control plane committed {@code QUEUED -> ASSIGNED}. Capture queue wait
 * at assign time; {@code assignedAt} is cleared when the worker starts.
 *
 * <p>Assignment wait is {@code startedAt - assignedAt}: delay between targeted
 * RabbitMQ assignment and {@code POST /internal/operations/{id}/start}. Capture
 * it in start handling before {@code markRunning} clears {@code assignedAt}.
 *
 * <p>Job end-to-end latency is {@code terminalTime - Job.createdAt}. Jobs have no
 * dedicated completedAt column; the terminal transition instant (updatedAt at
 * that refresh) is the terminal time. That value is visible on traces as the
 * root HTTP/job span duration and can be derived from persisted timestamps. It
 * is not a separate Prometheus metric in this phase.
 */
public final class OperationTimings {

	private OperationTimings() {
	}

	public static Optional<Duration> queueWait(Instant queuedAt, Instant assignedAt) {
		return positiveBetween(queuedAt, assignedAt);
	}

	public static Optional<Duration> assignmentWait(Instant assignedAt, Instant startedAt) {
		return positiveBetween(assignedAt, startedAt);
	}

	public static Optional<Duration> jobE2e(Instant createdAt, Instant terminalAt) {
		return positiveBetween(createdAt, terminalAt);
	}

	private static Optional<Duration> positiveBetween(Instant start, Instant end) {
		if (start == null || end == null || end.isBefore(start)) {
			return Optional.empty();
		}
		return Optional.of(Duration.between(start, end));
	}
}
