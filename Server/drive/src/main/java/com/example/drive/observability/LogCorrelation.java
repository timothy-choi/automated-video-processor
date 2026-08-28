package com.example.drive.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.MDC;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

public final class LogCorrelation implements AutoCloseable {

	private final List<String> keys = new ArrayList<>();

	private LogCorrelation() {
	}

	public static LogCorrelation open(UUID jobId, UUID operationId, UUID attemptId, String workerId) {
		LogCorrelation correlation = new LogCorrelation();
		correlation.put("job_id", jobId);
		correlation.put("operation_id", operationId);
		correlation.put("attempt_id", attemptId);
		if (workerId != null && !workerId.isBlank() && !TelemetryRedaction.looksLikeSecret(workerId)) {
			MDC.put("worker_id", workerId);
			correlation.keys.add("worker_id");
		}
		SpanContext spanContext = Span.current().getSpanContext();
		if (spanContext.isValid()) {
			MDC.put("trace_id", spanContext.getTraceId());
			MDC.put("span_id", spanContext.getSpanId());
			correlation.keys.add("trace_id");
			correlation.keys.add("span_id");
		}
		return correlation;
	}

	private void put(String key, UUID value) {
		if (value == null) {
			return;
		}
		MDC.put(key, value.toString());
		keys.add(key);
	}

	@Override
	public void close() {
		for (String key : keys) {
			MDC.remove(key);
		}
	}
}
