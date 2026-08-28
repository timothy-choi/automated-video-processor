package scheduler

import (
	"context"
	"log"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/client"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/otelx"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/policy"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

type ControlClient interface {
	Snapshot(ctx context.Context) (model.Snapshot, error)
	Assign(ctx context.Context, placement model.Placement) (model.AssignResponse, error)
}

type Loop struct {
	Client       ControlClient
	Selector     policy.Selector
	PollInterval time.Duration
}

func (l *Loop) Run(ctx context.Context) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		wait := l.tick(ctx)
		if wait <= 0 {
			continue
		}
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}

func (l *Loop) tick(ctx context.Context) time.Duration {
	interval := l.PollInterval
	if interval <= 0 {
		interval = 500 * time.Millisecond
	}
	snapshot, err := l.Client.Snapshot(ctx)
	if err != nil {
		if ctx.Err() != nil {
			return 0
		}
		if client.IsUnauthorized(err) {
			log.Printf("event=snapshot_unauthorized")
			return interval
		}
		log.Printf("event=snapshot_failed err=%v", err)
		return interval
	}
	if len(snapshot.Operations) == 0 {
		log.Printf(
			"event=scheduler_idle operation_policy=%s worker_policy=%s operations=0",
			l.Selector.OperationPolicy,
			l.Selector.WorkerPolicy,
		)
		return interval
	}

	placement, ok := l.Selector.Select(snapshot)
	if op := operationOf(snapshot, placement.OperationID); op != nil {
		ctx = otelx.Continue(ctx, op.Traceparent, op.Tracestate)
	}

	ctx, snapshotSpan := otelx.Tracer().Start(ctx, "scheduler.snapshot")
	defer snapshotSpan.End()
	snapshotSpan.SetAttributes(
		attribute.String("media.scheduler.operation_policy", l.Selector.OperationPolicy),
		attribute.String("media.scheduler.worker_policy", l.Selector.WorkerPolicy),
		attribute.Int("scheduler.queued_operations", len(snapshot.Operations)),
	)

	ctx, selectOp := otelx.Tracer().Start(ctx, "scheduler.select_operation")
	if op := operationOf(snapshot, placement.OperationID); op != nil {
		selectOp.SetAttributes(
			attribute.String("media.operation.id", op.OperationID),
			attribute.String("media.operation.type", op.Type),
			attribute.String("media.job.id", op.JobID),
			attribute.String("media.scheduler.operation_policy", l.Selector.OperationPolicy),
		)
	}
	selectOp.End()
	if !ok {
		opType := ""
		for _, op := range snapshot.Operations {
			if op.OperationID == placement.OperationID {
				opType = op.Type
				break
			}
		}
		log.Printf(
			"%sevent=no_eligible_worker operation_policy=%s worker_policy=%s operationId=%s type=%s queued=%d",
			otelx.Prefix(ctx),
			l.Selector.OperationPolicy,
			l.Selector.WorkerPolicy,
			placement.OperationID,
			opType,
			len(snapshot.Operations),
		)
		return interval
	}

	ctx, selectWorker := otelx.Tracer().Start(ctx, "scheduler.select_worker")
	selectWorker.SetAttributes(
		attribute.String("media.worker.id", placement.WorkerID),
		attribute.String("media.scheduler.worker_policy", placement.WorkerPolicy),
		attribute.String("media.scheduler.operation_policy", placement.OperationPolicy),
	)
	if placement.WorkerPolicy == policy.LeastLoaded {
		selectWorker.SetAttributes(attribute.Int64("media.scheduler.active_operations", int64(placement.ActiveOperations)))
	}
	selectWorker.End()

	ctx, assignSpan := otelx.Tracer().Start(ctx, "scheduler.assign", trace.WithSpanKind(trace.SpanKindClient))
	assigned, err := l.Client.Assign(ctx, placement)
	if err != nil {
		otelx.RecordError(assignSpan, err)
		assignSpan.End()
		if client.IsConflict(err) {
			log.Printf(
				"%sevent=assign_conflict operationId=%s workerId=%s operation_policy=%s worker_policy=%s err=%v",
				otelx.Prefix(ctx),
				placement.OperationID,
				placement.WorkerID,
				placement.OperationPolicy,
				placement.WorkerPolicy,
				err,
			)
			return 0
		}
		if client.IsUnauthorized(err) {
			log.Printf("%sevent=assign_unauthorized operationId=%s workerId=%s", otelx.Prefix(ctx), placement.OperationID, placement.WorkerID)
			return interval
		}
		log.Printf(
			"%sevent=assign_failed operationId=%s workerId=%s err=%v",
			otelx.Prefix(ctx),
			placement.OperationID,
			placement.WorkerID,
			err,
		)
		return interval
	}
	assignSpan.SetAttributes(
		attribute.String("media.operation.id", assigned.OperationID),
		attribute.String("media.job.id", assigned.JobID),
		attribute.String("media.worker.id", assigned.WorkerID),
		attribute.String("media.scheduler.operation_policy", assigned.OperationPolicy),
		attribute.String("media.scheduler.worker_policy", assigned.WorkerPolicy),
	)
	assignSpan.End()
	otelx.RecordDecision(ctx, assigned.WorkerPolicy)
	log.Printf(
		"%sevent=assigned operationId=%s workerId=%s operation_policy=%s worker_policy=%s operation_type=%s selected_load=%d decisionId=%s routingKey=%s",
		otelx.Prefix(ctx),
		assigned.OperationID,
		assigned.WorkerID,
		assigned.OperationPolicy,
		assigned.WorkerPolicy,
		operationType(snapshot, assigned.OperationID),
		placement.ActiveOperations,
		assigned.DecisionID,
		assigned.RoutingKey,
	)
	return 0
}

func operationOf(snapshot model.Snapshot, operationID string) *model.Operation {
	for i := range snapshot.Operations {
		if snapshot.Operations[i].OperationID == operationID {
			return &snapshot.Operations[i]
		}
	}
	return nil
}

func operationType(snapshot model.Snapshot, operationID string) string {
	for _, op := range snapshot.Operations {
		if op.OperationID == operationID {
			return op.Type
		}
	}
	return ""
}
