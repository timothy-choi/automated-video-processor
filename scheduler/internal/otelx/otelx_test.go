package otelx

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

func TestHTTPPropagationRoundTrip(t *testing.T) {
	exporter, _ := installTest(t)
	ctx, span := Tracer().Start(context.Background(), "scheduler.assign")
	span.SetAttributes(attribute.String("media.job.id", "job-1"))
	defer span.End()

	var got string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("traceparent")
		incoming := ExtractHTTP(r.Context(), r.Header)
		sc := trace.SpanFromContext(incoming).SpanContext()
		if sc.TraceID().String() != span.SpanContext().TraceID().String() {
			t.Fatalf("trace=%s want=%s", sc.TraceID(), span.SpanContext().TraceID())
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/internal/scheduler/assign", nil)
	if err != nil {
		t.Fatal(err)
	}
	InjectHTTP(ctx, req.Header)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if got == "" || got[3:35] != span.SpanContext().TraceID().String() {
		t.Fatalf("traceparent=%q", got)
	}
	_ = exporter
}

func TestContinueUsesTraceparent(t *testing.T) {
	installTest(t)
	ctx, span := Tracer().Start(context.Background(), "job.persist")
	headers := map[string]string{}
	InjectMap(ctx, headers)
	span.End()
	continued := Continue(context.Background(), headers["traceparent"], headers["tracestate"])
	got := trace.SpanFromContext(continued).SpanContext()
	if got.TraceID() != span.SpanContext().TraceID() {
		t.Fatalf("trace=%s want=%s", got.TraceID(), span.SpanContext().TraceID())
	}
}

func TestIdleSnapshotIsNotTracedByTransport(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = r.Header.Get("traceparent") != ""
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	client := &http.Client{Transport: WrapTransport(http.DefaultTransport)}
	resp, err := client.Get(server.URL + "/internal/scheduler/snapshot")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if called {
		t.Fatal("idle snapshot should not inject traceparent")
	}
}

func TestSetupWithUnreachableExporterDoesNotFail(t *testing.T) {
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:1")
	t.Setenv("OTEL_TRACES_EXPORTER", "none")
	t.Setenv("OTEL_METRICS_EXPORTER", "none")
	t.Setenv("OTEL_TRACES_SAMPLER", "always_on")
	shutdown, err := Setup(context.Background(), Config{ServiceName: "media-scheduler"})
	if err != nil {
		t.Fatal(err)
	}
	if shutdown == nil {
		t.Fatal("expected shutdown func")
	}
	_, span := Tracer().Start(context.Background(), "scheduler.snapshot")
	span.End()
	RecordDecision(context.Background(), "LEAST_LOADED")
	if err := shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestSignalURLAppendsOTLPPaths(t *testing.T) {
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318")
	if got := signalURL("/v1/traces"); got != "http://otel-collector:4318/v1/traces" {
		t.Fatalf("traces=%s", got)
	}
	if got := signalURL("/v1/metrics"); got != "http://otel-collector:4318/v1/metrics" {
		t.Fatalf("metrics=%s", got)
	}
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318/v1/traces")
	if got := signalURL("/v1/traces"); got != "http://otel-collector:4318/v1/traces" {
		t.Fatalf("already-pathed=%s", got)
	}
}

func TestRecordDecisionIncrements(t *testing.T) {
	_, reader := installTest(t)
	RecordDecision(context.Background(), "LEAST_LOADED")
	var rm metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &rm); err != nil {
		t.Fatal(err)
	}
	if count := counterValue(rm, "media.scheduler.decisions"); count != 1 {
		t.Fatalf("count=%v", count)
	}
}

func installTest(t *testing.T) (*tracetest.InMemoryExporter, *sdkmetric.ManualReader) {
	t.Helper()
	exp := tracetest.NewInMemoryExporter()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exp), sdktrace.WithSampler(sdktrace.AlwaysSample()))
	reader := sdkmetric.NewManualReader()
	mp := sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader))
	prevTP := otel.GetTracerProvider()
	prevMP := otel.GetMeterProvider()
	prevProp := otel.GetTextMapPropagator()
	otel.SetTracerProvider(tp)
	otel.SetMeterProvider(mp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	t.Cleanup(func() {
		otel.SetTracerProvider(prevTP)
		otel.SetMeterProvider(prevMP)
		otel.SetTextMapPropagator(prevProp)
		_ = tp.Shutdown(context.Background())
		_ = mp.Shutdown(context.Background())
	})
	return exp, reader
}

func counterValue(rm metricdata.ResourceMetrics, name string) int64 {
	for _, sm := range rm.ScopeMetrics {
		for _, m := range sm.Metrics {
			if m.Name != name {
				continue
			}
			sum, ok := m.Data.(metricdata.Sum[int64])
			if !ok {
				continue
			}
			var total int64
			for _, dp := range sum.DataPoints {
				total += dp.Value
			}
			return total
		}
	}
	return 0
}
