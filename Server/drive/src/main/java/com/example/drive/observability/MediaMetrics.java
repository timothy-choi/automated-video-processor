package com.example.drive.observability;

import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.example.drive.job.domain.JobStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class MediaMetrics {

	private static final String TYPE = "type";
	private static final String WORKER_POLICY = "worker_policy";

	private final MeterRegistry registry;

	public MediaMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	public void jobSubmitted() {
		increment("media.jobs.submitted");
	}

	public void jobTransition(JobStatus previous, JobStatus next) {
		if (previous == next || next == null) {
			return;
		}
		switch (next) {
			case COMPLETED -> increment("media.jobs.completed");
			case FAILED -> increment("media.jobs.failed");
			case CANCELLED -> increment("media.jobs.cancelled");
			default -> {
			}
		}
	}

	public void operationCompleted(String type) {
		increment("media.operations.completed", TYPE, boundedType(type));
	}

	public void operationFailed(String type) {
		increment("media.operations.failed", TYPE, boundedType(type));
	}

	public void recordRuntime(String type, Long actualRuntimeMs) {
		if (actualRuntimeMs == null || actualRuntimeMs < 0) {
			return;
		}
		recordTimer("media.operation.runtime", boundedType(type), Duration.ofMillis(actualRuntimeMs));
	}

	public void recordQueueWait(String type, Duration wait) {
		recordTimer("media.operation.queue.wait", boundedType(type), wait);
	}

	public void recordAssignmentWait(String type, Duration wait) {
		recordTimer("media.operation.assignment.wait", boundedType(type), wait);
	}

	public void schedulerDecision(String workerPolicy) {
		increment("media.scheduler.decisions", WORKER_POLICY, boundedPolicy(workerPolicy));
	}

	private void increment(String name) {
		if (registry == null) {
			return;
		}
		try {
			Counter.builder(name).register(registry).increment();
		}
		catch (RuntimeException ignored) {
			// Telemetry must not affect Job handling.
		}
	}

	private void increment(String name, String tag, String value) {
		if (registry == null) {
			return;
		}
		try {
			Counter.builder(name).tag(tag, value).register(registry).increment();
		}
		catch (RuntimeException ignored) {
			// Telemetry must not affect Job handling.
		}
	}

	private void recordTimer(String name, String type, Duration duration) {
		if (registry == null || duration == null || duration.isNegative()) {
			return;
		}
		try {
			Timer.builder(name)
					.tag(TYPE, type)
					.publishPercentileHistogram()
					.register(registry)
					.record(duration);
		}
		catch (RuntimeException ignored) {
			// Telemetry must not affect Job handling.
		}
	}

	static String boundedType(String type) {
		if (type == null || type.isBlank()) {
			return "UNKNOWN";
		}
		return switch (type.trim().toUpperCase(Locale.ROOT)) {
			case "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P", "H264_TO_AV1" ->
					type.trim().toUpperCase(Locale.ROOT);
			default -> "OTHER";
		};
	}

	static String boundedPolicy(String workerPolicy) {
		if (workerPolicy == null || workerPolicy.isBlank()) {
			return "UNKNOWN";
		}
		return switch (workerPolicy.trim().toUpperCase(Locale.ROOT)) {
			case "LEXICOGRAPHIC", "ROUND_ROBIN", "LEAST_LOADED" -> workerPolicy.trim().toUpperCase(Locale.ROOT);
			default -> "OTHER";
		};
	}
}
