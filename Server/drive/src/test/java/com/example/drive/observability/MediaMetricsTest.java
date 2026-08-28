package com.example.drive.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.example.drive.job.domain.JobStatus;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MediaMetricsTest {

	@Test
	void recordsJobAndOperationLifecycleWithBoundedLabels() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		MediaMetrics metrics = new MediaMetrics(registry);

		metrics.jobSubmitted();
		metrics.jobTransition(JobStatus.QUEUED, JobStatus.COMPLETED);
		metrics.jobTransition(JobStatus.RUNNING, JobStatus.FAILED);
		metrics.jobTransition(JobStatus.RUNNING, JobStatus.CANCELLED);
		metrics.operationCompleted("METADATA");
		metrics.operationFailed("H264_TO_AV1");
		metrics.recordRuntime("THUMBNAIL", 1500L);
		metrics.recordQueueWait("METADATA", Duration.ofMillis(250));
		metrics.recordAssignmentWait("METADATA", Duration.ofMillis(80));
		metrics.schedulerDecision("LEAST_LOADED");
		metrics.operationCompleted("not-a-real-type");

		assertThat(registry.counter("media.jobs.submitted").count()).isEqualTo(1);
		assertThat(registry.counter("media.jobs.completed").count()).isEqualTo(1);
		assertThat(registry.counter("media.jobs.failed").count()).isEqualTo(1);
		assertThat(registry.counter("media.jobs.cancelled").count()).isEqualTo(1);
		assertThat(registry.counter("media.operations.completed", "type", "METADATA").count()).isEqualTo(1);
		assertThat(registry.counter("media.operations.failed", "type", "H264_TO_AV1").count()).isEqualTo(1);
		assertThat(registry.counter("media.operations.completed", "type", "OTHER").count()).isEqualTo(1);
		assertThat(registry.counter("media.scheduler.decisions", "worker_policy", "LEAST_LOADED").count()).isEqualTo(1);
		assertThat(registry.find("media.operation.runtime").timer().count()).isEqualTo(1);
		assertThat(registry.find("media.operation.queue.wait").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
				.isEqualTo(250);
		assertThat(registry.getMeters().stream().map(Meter::getId).flatMap(id -> id.getTags().stream()))
				.noneMatch(tag -> tag.getKey().equals("job_id") || tag.getKey().equals("operation_id") || tag.getKey().equals("worker_id"));
	}

	@Test
	void registryFailureDoesNotPropagate() {
		MediaMetrics metrics = new MediaMetrics(null);
		assertThatCode(() -> {
			metrics.jobSubmitted();
			metrics.operationFailed("METADATA");
			metrics.recordRuntime("METADATA", 10L);
		}).doesNotThrowAnyException();
	}
}
