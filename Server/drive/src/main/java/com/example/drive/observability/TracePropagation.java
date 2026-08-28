package com.example.drive.observability;

import java.util.HashMap;
import java.util.Map;

import org.springframework.amqp.core.MessageProperties;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;

public final class TracePropagation {

	public static final String TRACEPARENT = "traceparent";
	public static final String TRACESTATE = "tracestate";

	private TracePropagation() {
	}

	public record Captured(String traceparent, String tracestate) {
		public boolean isPresent() {
			return traceparent != null && !traceparent.isBlank();
		}
	}

	public static Captured capture() {
		return capture(Context.current());
	}

	public static Captured capture(Context context) {
		Map<String, String> carrier = new HashMap<>();
		W3CTraceContextPropagator.getInstance().inject(context, carrier, Map::put);
		return new Captured(carrier.get(TRACEPARENT), carrier.get(TRACESTATE));
	}

	public static Context restore(String traceparent, String tracestate) {
		if (traceparent == null || traceparent.isBlank()) {
			return Context.root();
		}
		Map<String, String> carrier = new HashMap<>();
		carrier.put(TRACEPARENT, traceparent);
		if (tracestate != null && !tracestate.isBlank()) {
			carrier.put(TRACESTATE, tracestate);
		}
		return W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, MAP_GETTER);
	}

	private static final io.opentelemetry.context.propagation.TextMapGetter<Map<String, String>> MAP_GETTER =
			new io.opentelemetry.context.propagation.TextMapGetter<>() {
				@Override
				public Iterable<String> keys(Map<String, String> carrier) {
					return carrier.keySet();
				}

				@Override
				public String get(Map<String, String> carrier, String key) {
					if (carrier == null || key == null) {
						return null;
					}
					return carrier.get(key);
				}
			};

	public static void injectAmqp(MessageProperties properties, Context context) {
		W3CTraceContextPropagator.getInstance().inject(context, properties, (props, key, value) -> {
			if (key == null || value == null || TelemetryRedaction.isSensitiveKey(key)) {
				return;
			}
			props.setHeader(key, value);
		});
	}

	public static String formatTraceparent(SpanContext spanContext) {
		if (spanContext == null || !spanContext.isValid()) {
			return null;
		}
		String sampled = spanContext.isSampled() ? "01" : "00";
		return "00-" + spanContext.getTraceId() + "-" + spanContext.getSpanId() + "-" + sampled;
	}

	public static String currentTraceparent() {
		return formatTraceparent(Span.current().getSpanContext());
	}
}
