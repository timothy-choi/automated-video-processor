package com.example.drive.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.drive.job.JobService;
import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.OperationType;
import com.example.drive.job.dto.CreateJobRequest;
import com.example.drive.job.dto.CreateOperationRequest;
import com.example.drive.job.repository.ArtifactRepository;
import com.example.drive.job.repository.ExecutionAttemptRepository;
import com.example.drive.job.repository.JobRepository;
import com.example.drive.job.repository.OperationRepository;
import com.example.drive.media.MediaAssetService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class JobServiceTelemetryIsolationTest {

	@Test
	void metricExporterFailureDoesNotFailJobCreate() {
		JobRepository jobs = mock(JobRepository.class);
		when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
		MediaMetrics metrics = new MediaMetrics(null);
		JobService service = new JobService(
				jobs,
				mock(OperationRepository.class),
				mock(ArtifactRepository.class),
				mock(ExecutionAttemptRepository.class),
				mock(MediaAssetService.class),
				Clock.fixed(Instant.parse("2026-08-27T21:00:00Z"), ZoneOffset.UTC),
				metrics,
				OpenTelemetry.noop().getTracer("test")
		);
		assertThatCode(() -> service.createJob(
				new CreateJobRequest(
						"s3://media-input/video.mp4",
						List.of(new CreateOperationRequest(OperationType.METADATA)),
						null,
						null
				),
				UUID.randomUUID()
		)).doesNotThrowAnyException();
	}

	@Test
	void createJobRecordsSubmittedMetric() {
		JobRepository jobs = mock(JobRepository.class);
		when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		JobService service = new JobService(
				jobs,
				mock(OperationRepository.class),
				mock(ArtifactRepository.class),
				mock(ExecutionAttemptRepository.class),
				mock(MediaAssetService.class),
				Clock.fixed(Instant.parse("2026-08-27T21:00:00Z"), ZoneOffset.UTC),
				new MediaMetrics(registry),
				OpenTelemetry.noop().getTracer("test")
		);
		var created = service.createJob(
				new CreateJobRequest(
						"s3://media-input/video.mp4",
						List.of(new CreateOperationRequest(OperationType.METADATA)),
						null,
						null
				),
				UUID.randomUUID()
		);
		assertThat(created.id()).isNotNull();
		assertThat(registry.counter("media.jobs.submitted").count()).isEqualTo(1);
	}

	@Test
	void createJobCapturesCurrentTraceparent() {
		JobRepository jobs = mock(JobRepository.class);
		java.util.concurrent.atomic.AtomicReference<Job> saved = new java.util.concurrent.atomic.AtomicReference<>();
		when(jobs.save(any(Job.class))).thenAnswer(invocation -> {
			Job job = invocation.getArgument(0);
			saved.set(job);
			return job;
		});
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder()
				.addSpanProcessor(SimpleSpanProcessor.create(exporter))
				.build();
		OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
		try {
			Span span = sdk.getTracer("test").spanBuilder("http.post").startSpan();
			try (Scope ignored = span.makeCurrent()) {
				new JobService(
						jobs,
						mock(OperationRepository.class),
						mock(ArtifactRepository.class),
						mock(ExecutionAttemptRepository.class),
						mock(MediaAssetService.class),
						Clock.fixed(Instant.parse("2026-08-27T21:00:00Z"), ZoneOffset.UTC),
						new MediaMetrics(new SimpleMeterRegistry()),
						sdk.getTracer("test")
				).createJob(
						new CreateJobRequest(
								"s3://media-input/video.mp4",
								List.of(new CreateOperationRequest(OperationType.METADATA)),
								null,
								null
						),
						UUID.randomUUID()
				);
			}
			finally {
				span.end();
			}
			assertThat(saved.get()).isNotNull();
			assertThat(saved.get().getTraceparent()).contains(span.getSpanContext().getTraceId());
		}
		finally {
			provider.close();
		}
	}
}
