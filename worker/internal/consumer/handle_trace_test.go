package consumer

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/run"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

func TestHandleRecordsMediaSpanAndRuntimeMetric(t *testing.T) {
	exp, reader := installTrace(t)
	ctrl := &fakeControl{start: startedOK()}
	decision := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Result{RuntimeMs: 12}, nil
	})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	names := spanNames(exp)
	if !names["worker.consume"] || !names["operation.start"] || !names["media.execute"] {
		t.Fatalf("spans=%v", names)
	}
	var rm metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &rm); err != nil {
		t.Fatal(err)
	}
	if histogramCount(rm, "media.operation.runtime") != 1 {
		t.Fatalf("runtime metric missing: %+v", rm)
	}
}

func TestHandleFailureSpanIsErrorAndIncrementsFailureMetric(t *testing.T) {
	exp, reader := installTrace(t)
	ctrl := &fakeControl{start: startedOK()}
	decision := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Result{RuntimeMs: 8}, errors.New("ffprobe failed")
	})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	found := false
	for _, span := range exp.GetSpans() {
		if span.Name == "media.execute" {
			found = true
			if span.Status.Code != codes.Error {
				t.Fatalf("status=%v", span.Status)
			}
		}
	}
	if !found {
		t.Fatal("missing media.execute")
	}
	var rm metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &rm); err != nil {
		t.Fatal(err)
	}
	if counterValue(rm, "media.operations.failed") != 1 {
		t.Fatalf("failed metric missing")
	}
}

func TestHandleCancellationIsNotErrorStatus(t *testing.T) {
	exp, _ := installTrace(t)
	ctrl := &fakeControl{start: startedOK(), cancelOnRenew: 1}
	decision := HandleWithOptions(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		<-ctx.Done()
		return run.Result{RuntimeMs: 4}, ctx.Err()
	}, Options{RenewInterval: 5 * time.Millisecond})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	for _, span := range exp.GetSpans() {
		if span.Name != "media.execute" {
			continue
		}
		if span.Status.Code == codes.Error {
			t.Fatalf("cancellation marked error: %v", span.Status)
		}
		cancelled := false
		for _, attr := range span.Attributes {
			if string(attr.Key) == "operation.cancelled" && attr.Value.AsBool() {
				cancelled = true
			}
		}
		if !cancelled {
			t.Fatalf("missing operation.cancelled attrs=%v", span.Attributes)
		}
	}
}

func TestHandleContinuesExtractedTrace(t *testing.T) {
	exp, _ := installTrace(t)
	ctx, parent := otel.Tracer("test").Start(context.Background(), "rabbitmq.publish", trace.WithSpanKind(trace.SpanKindProducer))
	parent.End()
	ctrl := &fakeControl{start: startedOK()}
	Handle(ctx, "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Result{RuntimeMs: 1}, nil
	})
	traceID := parent.SpanContext().TraceID()
	found := false
	for _, span := range exp.GetSpans() {
		if span.Name == "worker.consume" && span.SpanContext.TraceID() == traceID {
			found = true
		}
	}
	if !found {
		t.Fatalf("worker.consume not in parent trace %s spans=%v", traceID, spanNames(exp))
	}
}

func TestHandleWorksWhenExporterUnavailable(t *testing.T) {
	ctrl := &fakeControl{start: startedOK()}
	decision := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Result{RuntimeMs: 2}, nil
	})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
}

func installTrace(t *testing.T) (*tracetest.InMemoryExporter, *sdkmetric.ManualReader) {
	t.Helper()
	exp := tracetest.NewInMemoryExporter()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exp), sdktrace.WithSampler(sdktrace.AlwaysSample()))
	reader := sdkmetric.NewManualReader()
	mp := sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader))
	otel.SetTracerProvider(tp)
	otel.SetMeterProvider(mp)
	otel.SetTextMapPropagator(propagation.TraceContext{})
	t.Cleanup(func() {
		_ = tp.Shutdown(context.Background())
		_ = mp.Shutdown(context.Background())
	})
	return exp, reader
}

func spanNames(exp *tracetest.InMemoryExporter) map[string]bool {
	names := map[string]bool{}
	for _, span := range exp.GetSpans() {
		names[span.Name] = true
	}
	return names
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
