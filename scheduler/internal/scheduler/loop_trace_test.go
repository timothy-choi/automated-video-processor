package scheduler

import (
	"context"
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/policy"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
)

func TestTickCreatesSchedulingSpansAndDecisionMetric(t *testing.T) {
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

	ctrl := &fakeControl{
		snapshot: model.Snapshot{
			Operations: []model.Operation{
				{OperationID: "op-1", JobID: "job-1", Type: "METADATA", CreatedAt: time.Now()},
			},
			Workers: []model.Worker{
				{ID: "worker-a", Status: "AVAILABLE", SupportedOperations: []string{"METADATA"}, ActiveOperations: 2},
			},
		},
		assign: model.AssignResponse{DecisionID: "dec-1", OperationID: "op-1", JobID: "job-1", WorkerID: "worker-a", OperationPolicy: "FIFO", WorkerPolicy: "LEAST_LOADED"},
	}
	loop := &Loop{Client: ctrl, Selector: mustSelector(t, policy.FIFO, policy.LeastLoaded)}
	if wait := loop.tick(context.Background()); wait != 0 {
		t.Fatalf("wait=%s", wait)
	}
	names := map[string]bool{}
	for _, span := range exp.GetSpans() {
		names[span.Name] = true
		if span.Name == "scheduler.select_worker" {
			got := attribute.Key("media.scheduler.active_operations")
			found := false
			for _, attr := range span.Attributes {
				if attr.Key == got {
					found = true
				}
			}
			if !found {
				t.Fatalf("missing active_operations attrs=%v", span.Attributes)
			}
		}
	}
	for _, name := range []string{"scheduler.snapshot", "scheduler.select_operation", "scheduler.select_worker", "scheduler.assign"} {
		if !names[name] {
			t.Fatalf("missing span %s in %v", name, names)
		}
	}
	var rm metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &rm); err != nil {
		t.Fatal(err)
	}
	if count := counterValue(rm, "media.scheduler.decisions"); count != 1 {
		t.Fatalf("decisions=%d metrics=%+v", count, rm)
	}
}

func TestTickIdleDoesNotCreateSpans(t *testing.T) {
	exp := tracetest.NewInMemoryExporter()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exp), sdktrace.WithSampler(sdktrace.AlwaysSample()))
	otel.SetTracerProvider(tp)
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })
	ctrl := &fakeControl{snapshot: model.Snapshot{}}
	loop := &Loop{Client: ctrl, Selector: mustSelector(t, policy.FIFO, policy.Lexicographic), PollInterval: time.Millisecond}
	loop.tick(context.Background())
	if len(exp.GetSpans()) != 0 {
		t.Fatalf("idle spans=%v", exp.GetSpans())
	}
}

func TestTickContinuesJobTraceparent(t *testing.T) {
	exp := tracetest.NewInMemoryExporter()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exp), sdktrace.WithSampler(sdktrace.AlwaysSample()))
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.TraceContext{})
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })

	ctx, parent := otel.Tracer("test").Start(context.Background(), "job.persist")
	carrier := propagation.MapCarrier{}
	otel.GetTextMapPropagator().Inject(ctx, carrier)
	parent.End()
	if carrier.Get("traceparent") == "" {
		t.Fatal("expected injected traceparent")
	}

	ctrl := &fakeControl{
		snapshot: model.Snapshot{
			Operations: []model.Operation{
				{OperationID: "op-1", JobID: "job-1", Type: "METADATA", CreatedAt: time.Now(), Traceparent: carrier.Get("traceparent"), Tracestate: carrier.Get("tracestate")},
			},
			Workers: []model.Worker{
				{ID: "worker-a", Status: "AVAILABLE", SupportedOperations: []string{"METADATA"}},
			},
		},
		assign: model.AssignResponse{DecisionID: "dec-1", OperationID: "op-1", JobID: "job-1", WorkerID: "worker-a", OperationPolicy: "FIFO", WorkerPolicy: "LEXICOGRAPHIC"},
	}
	loop := &Loop{Client: ctrl, Selector: mustSelector(t, policy.FIFO, policy.Lexicographic)}
	loop.tick(context.Background())
	want := parent.SpanContext().TraceID()
	found := false
	for _, span := range exp.GetSpans() {
		if span.Name == "scheduler.snapshot" && span.SpanContext.TraceID() == want {
			found = true
		}
	}
	if !found {
		t.Fatalf("scheduler.snapshot not in job trace %s spans=%v", want, spanNames(exp))
	}
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
