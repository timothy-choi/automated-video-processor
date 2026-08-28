package com.example.drive.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

public final class MediaSpans {

	public static final String JOB_PERSIST = "job.persist";
	public static final String SCHEDULER_ASSIGN = "scheduler.assign";
	public static final String RABBITMQ_PUBLISH = "rabbitmq.publish";
	public static final String OPERATION_START = "operation.start";
	public static final String OPERATION_COMPLETE = "operation.complete";
	public static final String OPERATION_FAIL = "operation.fail";
	public static final String OPERATION_CANCELLED = "operation.cancelled";
	public static final String OBJECTSTORE_HEAD = "objectstore.head";
	public static final String OBJECTSTORE_PRESIGN = "objectstore.presign";
	public static final String OBJECTSTORE_DELETE = "objectstore.delete";
	public static final String MEDIA_ASSET_CREATE = "media_asset.create";
	public static final String MEDIA_ASSET_PRESIGN_UPLOAD = "media_asset.presign_upload";
	public static final String MEDIA_ASSET_COMPLETE = "media_asset.complete";

	private MediaSpans() {
	}

	public static void set(Span span, AttributeKey<String> key, String value) {
		if (span == null || key == null || value == null || value.isBlank()) {
			return;
		}
		if (TelemetryRedaction.isSensitiveKey(key.getKey()) || TelemetryRedaction.looksLikeSecret(value)) {
			return;
		}
		span.setAttribute(key, value);
	}

	public static void set(Span span, AttributeKey<Long> key, Long value) {
		if (span == null || key == null || value == null) {
			return;
		}
		span.setAttribute(key, value);
	}

	public static void recordError(Span span, Throwable error) {
		if (span == null || error == null) {
			return;
		}
		span.setStatus(StatusCode.ERROR, TelemetryRedaction.boundedMessage(error.getMessage()));
	}

	public static Scope makeCurrent(Span span) {
		return span.makeCurrent();
	}

	public static Span start(Tracer tracer, String name) {
		if (tracer == null) {
			return Span.getInvalid();
		}
		return tracer.spanBuilder(name).startSpan();
	}
}
