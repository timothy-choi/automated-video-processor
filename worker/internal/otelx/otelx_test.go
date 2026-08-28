package otelx

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

func TestAMQPRoundTripPreservesTrace(t *testing.T) {
	exp, _ := installTest(t)
	ctx, span := Tracer().Start(context.Background(), "rabbitmq.publish", trace.WithSpanKind(trace.SpanKindProducer))
	headers := amqp.Table{}
	InjectAMQP(ctx, headers)
	span.End()
	extracted := ExtractAMQP(context.Background(), headers)
	got := trace.SpanFromContext(extracted).SpanContext()
	if got.TraceID() != span.SpanContext().TraceID() {
		t.Fatalf("trace=%s want=%s headers=%v", got.TraceID(), span.SpanContext().TraceID(), headers)
	}
	_ = exp
}

func TestControlClientInjectsTraceparent(t *testing.T) {
	installTest(t)
	var got string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("traceparent")
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	ctx, span := Tracer().Start(context.Background(), "operation.start")
	defer span.End()
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/internal/operations/op/start", nil)
	resp, err := (&http.Client{Transport: WrapTransport(http.DefaultTransport)}).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if got == "" || !contains(got, span.SpanContext().TraceID().String()) {
		t.Fatalf("traceparent=%q", got)
	}
}

func TestHeartbeatIsNotTraced(t *testing.T) {
	var got string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("traceparent")
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	req, _ := http.NewRequest(http.MethodPost, server.URL+"/internal/workers/worker-a/heartbeat", nil)
	resp, err := (&http.Client{Transport: WrapTransport(http.DefaultTransport)}).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if got != "" {
		t.Fatalf("heartbeat traced: %s", got)
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
}

func TestRuntimeAndFailureMetrics(t *testing.T) {
	_, reader := installTest(t)
	RecordRuntime(context.Background(), "METADATA", 1500*time.Millisecond)
	RecordOperationFailed(context.Background(), "H264_TO_AV1")
	SetAvailable(1)
	AddRunning(1)
	var rm metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &rm); err != nil {
		t.Fatal(err)
	}
	if histogramCount(rm, "media.operation.runtime") != 1 {
		t.Fatalf("runtime histogram missing: %+v", rm)
	}
	if counterValue(rm, "media.operations.failed") != 1 {
		t.Fatalf("failed counter missing")
	}
}

func TestSetupUnreachableExporterDoesNotFail(t *testing.T) {
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:1")
	t.Setenv("OTEL_TRACES_EXPORTER", "none")
	t.Setenv("OTEL_METRICS_EXPORTER", "none")
	shutdown, err := Setup(context.Background(), Config{ServiceName: "media-worker"})
	if err != nil {
		t.Fatal(err)
	}
	_, span := Tracer().Start(context.Background(), "media.execute")
	RecordError(span, context.DeadlineExceeded)
	span.End()
	if shutdown != nil {
		_ = shutdown(context.Background())
	}
}

func TestBoundRedactsSecrets(t *testing.T) {
	if Bound("http://minio/x?X-Amz-Signature=abc") != "redacted" {
		t.Fatal(Bound("http://minio/x?X-Amz-Signature=abc"))
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

func contains(s, sub string) bool {
	return len(s) >= len(sub) && (s == sub || len(sub) == 0 || (len(s) > 0 && (s[3:35] == sub || len(sub) > 0 && stringContains(s, sub))))
}

func stringContains(s, sub string) bool {
	return len(sub) == 0 || (len(s) >= len(sub) && (s == sub || func() bool {
		for i := 0; i+len(sub) <= len(s); i++ {
			if s[i:i+len(sub)] == sub {
				return true
			}
		}
		return false
	}()))
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

func histogramCount(rm metricdata.ResourceMetrics, name string) int64 {
	for _, sm := range rm.ScopeMetrics {
		for _, m := range sm.Metrics {
			if m.Name != name {
				continue
			}
			hist, ok := m.Data.(metricdata.Histogram[float64])
			if !ok {
				continue
			}
			var total int64
			for _, dp := range hist.DataPoints {
				total += int64(dp.Count)
			}
			return total
		}
	}
	return 0
}
