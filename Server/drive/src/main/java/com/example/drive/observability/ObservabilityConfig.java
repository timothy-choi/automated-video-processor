package com.example.drive.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

@Configuration
public class ObservabilityConfig {

	public static final String INSTRUMENTATION_NAME = "media-control-service";

	@Bean
	Tracer mediaTracer(OpenTelemetry openTelemetry) {
		return openTelemetry.getTracer(INSTRUMENTATION_NAME);
	}

	@Bean
	ObservationPredicate skipNoisyHttpServerSpans() {
		return (name, context) -> {
			if (context instanceof ServerRequestObservationContext server) {
				return !TelemetryNoise.isNoisyPath(server.getCarrier().getRequestURI());
			}
			return true;
		};
	}
}
