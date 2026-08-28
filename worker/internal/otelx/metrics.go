package otelx

import (
	"context"
	"strings"
	"sync/atomic"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

var (
	available atomic.Int64
	running   atomic.Int64
	gauges    atomic.Bool
)

func SetAvailable(v int64) {
	available.Store(v)
	ensureWorkerGauges()
}

func AddRunning(delta int64) {
	running.Add(delta)
	ensureWorkerGauges()
}

func RecordRuntime(ctx context.Context, operationType string, duration time.Duration) {
	if duration < 0 {
		return
	}
	h, err := otel.Meter(instrumentation).Float64Histogram("media.operation.runtime", metric.WithUnit("s"))
	if err != nil {
		return
	}
	h.Record(ctx, duration.Seconds(), metric.WithAttributes(typeAttr(operationType)))
}

func RecordOperationCompleted(ctx context.Context, operationType string) {
	c, err := otel.Meter(instrumentation).Int64Counter("media.operations.completed")
	if err != nil {
		return
	}
	c.Add(ctx, 1, metric.WithAttributes(typeAttr(operationType)))
}

func RecordOperationFailed(ctx context.Context, operationType string) {
	c, err := otel.Meter(instrumentation).Int64Counter("media.operations.failed")
	if err != nil {
		return
	}
	c.Add(ctx, 1, metric.WithAttributes(typeAttr(operationType)))
}

func typeAttr(operationType string) attribute.KeyValue {
	switch strings.ToUpper(strings.TrimSpace(operationType)) {
	case "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P", "H264_TO_AV1":
		return attribute.String("type", strings.ToUpper(strings.TrimSpace(operationType)))
	default:
		return attribute.String("type", "OTHER")
	}
}

func ensureWorkerGauges() {
	if gauges.Swap(true) {
		return
	}
	meter := otel.Meter(instrumentation)
	_, _ = meter.Int64ObservableGauge("media.worker.available", metric.WithInt64Callback(func(ctx context.Context, observer metric.Int64Observer) error {
		observer.Observe(available.Load())
		return nil
	}))
	_, _ = meter.Int64ObservableGauge("media.worker.running_operations", metric.WithInt64Callback(func(ctx context.Context, observer metric.Int64Observer) error {
		observer.Observe(running.Load())
		return nil
	}))
}
