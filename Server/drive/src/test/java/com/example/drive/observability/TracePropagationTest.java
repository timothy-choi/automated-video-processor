package com.example.drive.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class TracePropagationTest {

	@Test
	void injectsW3cHeadersIntoAmqpAndRestoresTheSameTrace() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder()
				.addSpanProcessor(SimpleSpanProcessor.create(exporter))
				.build();
		OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
		Tracer tracer = sdk.getTracer("test");
		Span parent = tracer.spanBuilder("assign").setSpanKind(SpanKind.SERVER).startSpan();
		try (Scope ignored = parent.makeCurrent()) {
			TracePropagation.Captured captured = TracePropagation.capture();
			assertThat(captured.isPresent()).isTrue();
			assertThat(captured.traceparent()).contains(parent.getSpanContext().getTraceId());

			MessageProperties properties = new MessageProperties();
			TracePropagation.injectAmqp(properties, Context.current());
			assertThat(properties.getHeaders()).containsKey(TracePropagation.TRACEPARENT);
			assertThat(properties.getHeaders().get(TracePropagation.TRACEPARENT).toString())
					.contains(parent.getSpanContext().getTraceId());
			assertThat(properties.getHeaders().keySet())
					.noneMatch(TelemetryRedaction::isSensitiveKey);

			Context restored = TracePropagation.restore(captured.traceparent(), captured.tracestate());
			SpanContext restoredContext = Span.fromContext(restored).getSpanContext();
			assertThat(restoredContext.getTraceId()).isEqualTo(parent.getSpanContext().getTraceId());
			assertThat(restoredContext.getSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
		}
		finally {
			parent.end();
			provider.close();
		}
	}

	@Test
	void spanAttributesSkipSecretsAndSignedUrls() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder()
				.addSpanProcessor(SimpleSpanProcessor.create(exporter))
				.build();
		try {
			Span span = provider.get("test").spanBuilder("job.persist").startSpan();
			MediaSpans.set(span, MediaAttributes.JOB_ID, "job-1");
			MediaSpans.set(span, MediaAttributes.ACCOUNT_ID, "mp_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
			MediaSpans.set(span, io.opentelemetry.api.common.AttributeKey.stringKey("signed_url"), "https://minio/x?X-Amz-Signature=abc");
			span.end();
			Attributes attributes = exporter.getFinishedSpanItems().getFirst().getAttributes();
			assertThat(attributes.get(MediaAttributes.JOB_ID)).isEqualTo("job-1");
			assertThat(attributes.get(MediaAttributes.ACCOUNT_ID)).isNull();
			assertThat(attributes.asMap().keySet())
					.noneMatch(key -> TelemetryRedaction.isSensitiveKey(key.getKey()));
		}
		finally {
			provider.close();
		}
	}

	@Test
	void restoreWithoutTraceparentIsRoot() {
		assertThat(Span.fromContext(TracePropagation.restore(null, null)).getSpanContext().isValid()).isFalse();
	}

	@Test
	void dispatchOutboxCapturesCurrentTrace() {
		InMemorySpanExporter exporter = InMemorySpanExporter.create();
		SdkTracerProvider provider = SdkTracerProvider.builder()
				.addSpanProcessor(SimpleSpanProcessor.create(exporter))
				.build();
		OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
		Span parent = sdk.getTracer("test").spanBuilder("scheduler.assign").startSpan();
		try (Scope ignored = parent.makeCurrent()) {
			com.example.drive.dispatch.DispatchOutbox outbox = new com.example.drive.dispatch.DispatchOutbox(
					java.util.UUID.randomUUID(),
					java.util.UUID.randomUUID(),
					"{\"assignmentId\":\"x\"}",
					java.time.Instant.parse("2026-08-27T21:00:00Z"),
					"worker.worker-a",
					"worker-a"
			);
			assertThat(outbox.getTraceparent()).contains(parent.getSpanContext().getTraceId());
		}
		finally {
			parent.end();
			provider.close();
		}
	}
}
