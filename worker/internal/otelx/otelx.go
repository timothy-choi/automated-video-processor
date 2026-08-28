package otelx

import (
	"context"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

const instrumentation = "media-platform"

type Config struct {
	ServiceName string
	Attributes  []attribute.KeyValue
}

func Setup(ctx context.Context, cfg Config) (func(context.Context) error, error) {
	if envFlag(os.Getenv("OTEL_SDK_DISABLED"), false) {
		return func(context.Context) error { return nil }, nil
	}
	serviceName := strings.TrimSpace(os.Getenv("OTEL_SERVICE_NAME"))
	if serviceName == "" {
		serviceName = cfg.ServiceName
	}
	attrs := append([]attribute.KeyValue{attribute.String("service.name", serviceName)}, cfg.Attributes...)
	res, err := resource.New(ctx,
		resource.WithFromEnv(),
		resource.WithTelemetrySDK(),
		resource.WithAttributes(attrs...),
	)
	if err != nil {
		log.Printf("event=otel_resource_failed err=%v", err)
		res = resource.Empty()
	}

	shutdowns := make([]func(context.Context) error, 0, 2)
	if tracesEnabled() {
		tp, stop, err := newTracerProvider(ctx, res)
		if err != nil {
			log.Printf("event=otel_tracer_init_failed err=%v", err)
		} else if tp != nil {
			otel.SetTracerProvider(tp)
			shutdowns = append(shutdowns, stop)
		}
	}
	if metricsEnabled() {
		mp, stop, err := newMeterProvider(ctx, res)
		if err != nil {
			log.Printf("event=otel_meter_init_failed err=%v", err)
		} else if mp != nil {
			otel.SetMeterProvider(mp)
			shutdowns = append(shutdowns, stop)
		}
	}
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))
	return func(ctx context.Context) error {
		var first error
		for i := len(shutdowns) - 1; i >= 0; i-- {
			if err := shutdowns[i](ctx); err != nil && first == nil {
				first = err
			}
		}
		return first
	}, nil
}

func Tracer() trace.Tracer {
	return otel.Tracer(instrumentation)
}

func Prefix(ctx context.Context) string {
	sc := trace.SpanFromContext(ctx).SpanContext()
	if !sc.IsValid() {
		return ""
	}
	return fmt.Sprintf("trace_id=%s span_id=%s ", sc.TraceID(), sc.SpanID())
}

func Bound(message string) string {
	msg := strings.TrimSpace(message)
	if looksLikeSecret(msg) {
		return "redacted"
	}
	if len(msg) > 256 {
		return msg[:256]
	}
	return msg
}

func RecordError(span trace.Span, err error) {
	if span == nil || err == nil {
		return
	}
	span.SetStatus(codes.Error, Bound(err.Error()))
}

func looksLikeSecret(value string) bool {
	lower := strings.ToLower(value)
	return strings.HasPrefix(lower, "mp_live_") ||
		strings.HasPrefix(lower, "mp_wk_") ||
		strings.HasPrefix(lower, "bearer ") ||
		strings.Contains(lower, "x-amz-signature") ||
		strings.Contains(lower, "authorization=")
}

func tracesEnabled() bool {
	if !envFlag(os.Getenv("OTEL_TRACES_ENABLED"), true) {
		return false
	}
	return !strings.EqualFold(strings.TrimSpace(os.Getenv("OTEL_TRACES_EXPORTER")), "none")
}

func metricsEnabled() bool {
	if !envFlag(os.Getenv("OTEL_METRICS_ENABLED"), true) {
		return false
	}
	return !strings.EqualFold(strings.TrimSpace(os.Getenv("OTEL_METRICS_EXPORTER")), "none")
}

func envFlag(raw string, defaultOn bool) bool {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "":
		return defaultOn
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return defaultOn
	}
}

func newTracerProvider(ctx context.Context, res *resource.Resource) (*sdktrace.TracerProvider, func(context.Context) error, error) {
	exp, err := otlptracehttp.New(ctx,
		otlptracehttp.WithEndpointURL(signalURL("/v1/traces")),
		otlptracehttp.WithTimeout(2*time.Second),
		otlptracehttp.WithRetry(otlptracehttp.RetryConfig{Enabled: false}),
	)
	if err != nil {
		return nil, nil, err
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sampler()),
		sdktrace.WithBatcher(exp,
			sdktrace.WithBatchTimeout(2*time.Second),
			sdktrace.WithExportTimeout(2*time.Second),
			sdktrace.WithMaxExportBatchSize(512),
		),
	)
	return tp, tp.Shutdown, nil
}

func newMeterProvider(ctx context.Context, res *resource.Resource) (*metric.MeterProvider, func(context.Context) error, error) {
	exp, err := otlpmetrichttp.New(ctx,
		otlpmetrichttp.WithEndpointURL(signalURL("/v1/metrics")),
		otlpmetrichttp.WithTimeout(2*time.Second),
		otlpmetrichttp.WithRetry(otlpmetrichttp.RetryConfig{Enabled: false}),
	)
	if err != nil {
		return nil, nil, err
	}
	mp := metric.NewMeterProvider(
		metric.WithResource(res),
		metric.WithReader(metric.NewPeriodicReader(exp, metric.WithInterval(10*time.Second), metric.WithTimeout(2*time.Second))),
	)
	return mp, mp.Shutdown, nil
}

func endpointURL() string {
	endpoint := strings.TrimSpace(os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT"))
	if endpoint == "" {
		endpoint = "http://localhost:4318"
	}
	return strings.TrimRight(endpoint, "/")
}

func signalURL(path string) string {
	base := endpointURL()
	if strings.HasSuffix(base, path) || strings.Contains(base, "/v1/") {
		return base
	}
	return base + path
}

func sampler() sdktrace.Sampler {
	name := strings.ToLower(strings.TrimSpace(os.Getenv("OTEL_TRACES_SAMPLER")))
	if name == "" {
		name = "parentbased_traceidratio"
	}
	ratio := 1.0
	if raw := strings.TrimSpace(os.Getenv("OTEL_TRACES_SAMPLER_ARG")); raw != "" {
		if parsed, err := strconv.ParseFloat(raw, 64); err == nil {
			ratio = parsed
		}
	}
	switch name {
	case "always_on":
		return sdktrace.AlwaysSample()
	case "always_off":
		return sdktrace.NeverSample()
	case "traceidratio":
		return sdktrace.TraceIDRatioBased(ratio)
	default:
		return sdktrace.ParentBased(sdktrace.TraceIDRatioBased(ratio))
	}
}
