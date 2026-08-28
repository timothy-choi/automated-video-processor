package otelx

import (
	"context"
	"strings"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

func RecordDecision(ctx context.Context, workerPolicy string) {
	c, err := otel.Meter(instrumentation).Int64Counter("media.scheduler.decisions")
	if err != nil {
		return
	}
	policy := strings.ToUpper(strings.TrimSpace(workerPolicy))
	switch policy {
	case "LEXICOGRAPHIC", "ROUND_ROBIN", "LEAST_LOADED":
	default:
		policy = "OTHER"
	}
	c.Add(ctx, 1, metric.WithAttributes(attribute.String("worker_policy", policy)))
}
