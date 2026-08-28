package consumer

import (
	"context"
	"errors"
	"log"
	"net/url"
	"sync/atomic"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/assignment"
	"github.com/timothy-choi/automated-video-processor/worker/internal/capability"
	"github.com/timothy-choi/automated-video-processor/worker/internal/client"
	"github.com/timothy-choi/automated-video-processor/worker/internal/lease"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/otelx"
	"github.com/timothy-choi/automated-video-processor/worker/internal/run"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

type Decision int

const (
	Ack Decision = iota
	NackRequeue
	NackDrop
)

func (d Decision) String() string {
	switch d {
	case Ack:
		return "ack"
	case NackRequeue:
		return "nack_requeue"
	case NackDrop:
		return "nack_drop"
	default:
		return "unknown"
	}
}

type Control interface {
	Start(ctx context.Context, operationID, workerID, assignmentID string) (model.StartResponse, error)
	Complete(ctx context.Context, operationID string, request model.CompleteRequest) error
	Fail(ctx context.Context, operationID string, runtimeMs *int64, reason, attemptID string) error
	Renew(ctx context.Context, operationID, attemptID, workerID string) (model.RenewResponse, error)
	Cancelled(ctx context.Context, operationID, attemptID, workerID string, runtimeMs int64) error
}

type Executor func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error)

type Options struct {
	Supported     []string
	RenewInterval time.Duration
}

func Handle(ctx context.Context, workerID string, body []byte, ctrl Control, exec Executor) Decision {
	return HandleWithOptions(ctx, workerID, body, ctrl, exec, Options{})
}

func HandleWithCapabilities(ctx context.Context, workerID string, supported []string, body []byte, ctrl Control, exec Executor) Decision {
	return HandleWithOptions(ctx, workerID, body, ctrl, exec, Options{Supported: supported})
}

func HandleWithOptions(ctx context.Context, workerID string, body []byte, ctrl Control, exec Executor, opts Options) Decision {
	ctx, consumeSpan := otelx.Tracer().Start(ctx, "worker.consume", trace.WithSpanKind(trace.SpanKindConsumer))
	defer consumeSpan.End()
	parsed, err := assignment.Parse(body)
	if err != nil {
		log.Printf("worker=%s event=malformed_message err=%v decision=%s", workerID, err, NackDrop)
		return NackDrop
	}
	consumeSpan.SetAttributes(
		attribute.String("media.job.id", parsed.JobID),
		attribute.String("media.operation.id", parsed.OperationID),
		attribute.String("media.operation.type", parsed.Type),
		attribute.String("media.worker.id", workerID),
	)
	supported := opts.Supported
	if supported == nil {
		supported = capability.ImplementedOperations()
	}
	if parsed.WorkerID != "" && parsed.WorkerID != workerID {
		log.Printf(
			"%sworker=%s assignment_worker=%s job=%s operation=%s type=%s event=worker_id_mismatch decision=%s",
			otelx.Prefix(ctx), workerID, parsed.WorkerID, parsed.JobID, parsed.OperationID, parsed.Type, NackDrop,
		)
		return NackDrop
	}
	if !supportsOperation(supported, parsed.Type) {
		log.Printf(
			"%sworker=%s job=%s operation=%s type=%s event=capability_mismatch decision=%s",
			otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, NackDrop,
		)
		return NackDrop
	}
	log.Printf(
		"%sworker=%s job=%s operation=%s type=%s event=received input=%s",
		otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, sanitizeURI(parsed.InputURI),
	)

	startCtx, startSpan := otelx.Tracer().Start(ctx, "operation.start")
	start, err := ctrl.Start(startCtx, parsed.OperationID, workerID, parsed.AssignmentID)
	if err != nil {
		otelx.RecordError(startSpan, err)
		startSpan.End()
		if client.IsUnavailable(err) {
			log.Printf(
				"%sworker=%s job=%s operation=%s type=%s event=start_unavailable err=%v decision=%s",
				otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, err, NackRequeue,
			)
			return NackRequeue
		}
		if client.IsConflict(err) {
			log.Printf(
				"%sworker=%s job=%s operation=%s assignment=%s type=%s event=stale_assignment_start_rejected err=%v decision=%s",
				otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.AssignmentID, parsed.Type, err, NackDrop,
			)
			return NackDrop
		}
		log.Printf(
			"%sworker=%s job=%s operation=%s type=%s event=start_rejected err=%v decision=%s",
			otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, err, NackDrop,
		)
		return NackDrop
	}
	if start.AttemptID != "" {
		startSpan.SetAttributes(attribute.String("media.attempt.id", start.AttemptID))
	}
	startSpan.End()

	switch start.Outcome {
	case model.StartAlreadyRunning, model.StartAlreadyTerminal:
		log.Printf(
			"worker=%s job=%s operation=%s type=%s event=duplicate_or_stale outcome=%s status=%s decision=%s",
			workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.Outcome, start.Status, Ack,
		)
		return Ack
	case model.StartInvalidState:
		log.Printf(
			"worker=%s job=%s operation=%s type=%s event=invalid_state status=%s decision=%s",
			workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.Status, NackDrop,
		)
		return NackDrop
	case model.StartStarted:
		if start.AttemptID == "" {
			log.Printf(
				"worker=%s job=%s operation=%s type=%s event=start_missing_attempt_id decision=%s",
				workerID, parsed.JobID, parsed.OperationID, parsed.Type, NackDrop,
			)
			return NackDrop
		}
	default:
		log.Printf(
			"worker=%s job=%s operation=%s type=%s event=unknown_start_outcome outcome=%s decision=%s",
			workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.Outcome, NackDrop,
		)
		return NackDrop
	}

	renewInterval := opts.RenewInterval
	if renewInterval <= 0 {
		renewInterval = lease.DefaultRenewInterval
	}
	execCtx, stopExec := context.WithCancel(ctx)
	defer stopExec()
	var userCancel atomic.Bool
	renewCtx, stopRenew := context.WithCancel(ctx)
	defer stopRenew()
	go lease.RunLoop(renewCtx, renewInterval, func(renewCallCtx context.Context) error {
		resp, err := ctrl.Renew(renewCallCtx, parsed.OperationID, start.AttemptID, workerID)
		if err != nil {
			if client.IsConflict(err) {
				userCancel.Store(true)
				stopExec()
			}
			return err
		}
		if resp.CancelRequested {
			userCancel.Store(true)
			stopExec()
		}
		return nil
	})

	log.Printf(
		"%sworker=%s job=%s operation=%s type=%s attempt=%s event=execution_start",
		otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.AttemptID,
	)
	otelx.AddRunning(1)
	defer otelx.AddRunning(-1)
	mediaCtx, mediaSpan := otelx.Tracer().Start(execCtx, "media.execute")
	mediaSpan.SetAttributes(
		attribute.String("media.job.id", parsed.JobID),
		attribute.String("media.operation.id", parsed.OperationID),
		attribute.String("media.operation.type", parsed.Type),
		attribute.String("media.attempt.id", start.AttemptID),
		attribute.String("media.worker.id", workerID),
	)
	result, execErr := exec(mediaCtx, parsed.Claimed())
	stopRenew()
	if userCancel.Load() {
		mediaSpan.SetAttributes(attribute.Bool("operation.cancelled", true))
		mediaSpan.End()
		decision := reportWithRetry(ctx, func(reportCtx context.Context) error {
			return ctrl.Cancelled(reportCtx, parsed.OperationID, start.AttemptID, workerID, result.RuntimeMs)
		})
		log.Printf(
			"%sworker=%s job=%s operation=%s type=%s attempt=%s event=execution_cancelled runtime_ms=%d decision=%s",
			otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.AttemptID, result.RuntimeMs, decision,
		)
		return decision
	}
	if execErr != nil {
		otelx.RecordError(mediaSpan, execErr)
		mediaSpan.End()
		if errors.Is(execErr, context.Canceled) || errors.Is(execErr, context.DeadlineExceeded) {
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				reason := "execution timeout exceeded"
				decision := reportWithRetry(ctx, func(reportCtx context.Context) error {
					return ctrl.Fail(reportCtx, parsed.OperationID, &result.RuntimeMs, reason, start.AttemptID)
				})
				otelx.RecordOperationFailed(ctx, parsed.Type)
				otelx.RecordRuntime(ctx, parsed.Type, time.Duration(result.RuntimeMs)*time.Millisecond)
				log.Printf(
					"%sworker=%s job=%s operation=%s type=%s attempt=%s event=execution_timeout runtime_ms=%d decision=%s",
					otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.AttemptID, result.RuntimeMs, decision,
				)
				return decision
			}
			log.Printf(
				"%sworker=%s job=%s operation=%s type=%s attempt=%s event=execution_interrupted err=%v decision=%s",
				otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.AttemptID, execErr, NackRequeue,
			)
			return NackRequeue
		}
		decision := reportWithRetry(ctx, func(reportCtx context.Context) error {
			return ctrl.Fail(reportCtx, parsed.OperationID, &result.RuntimeMs, execErr.Error(), start.AttemptID)
		})
		otelx.RecordOperationFailed(ctx, parsed.Type)
		otelx.RecordRuntime(ctx, parsed.Type, time.Duration(result.RuntimeMs)*time.Millisecond)
		log.Printf(
			"%sworker=%s job=%s operation=%s type=%s attempt=%s event=execution_failure runtime_ms=%d err=%v decision=%s",
			otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.AttemptID, result.RuntimeMs, execErr, decision,
		)
		return decision
	}
	mediaSpan.End()

	complete := model.CompleteRequest{AttemptID: start.AttemptID, ActualRuntimeMs: result.RuntimeMs}
	if result.Metadata != nil {
		complete.Metadata = result.Metadata
	}
	if result.Artifact != nil {
		complete.Artifact = result.Artifact
	}
	decision := reportWithRetry(ctx, func(reportCtx context.Context) error {
		return ctrl.Complete(reportCtx, parsed.OperationID, complete)
	})
	otelx.RecordOperationCompleted(ctx, parsed.Type)
	otelx.RecordRuntime(ctx, parsed.Type, time.Duration(result.RuntimeMs)*time.Millisecond)
	log.Printf(
		"%sworker=%s job=%s operation=%s type=%s attempt=%s event=execution_completed runtime_ms=%d decision=%s",
		otelx.Prefix(ctx), workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.AttemptID, result.RuntimeMs, decision,
	)
	return decision
}

func reportWithRetry(ctx context.Context, fn func(context.Context) error) Decision {
	var last error
	for attempt := 1; attempt <= 3; attempt++ {
		reportCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
		last = fn(reportCtx)
		cancel()
		if last == nil || client.IsConflict(last) {
			return Ack
		}
		if !client.IsUnavailable(last) {
			log.Printf("event=report_rejected attempt=%d err=%v decision=%s", attempt, last, NackDrop)
			return NackDrop
		}
		if attempt < 3 && !sleep(ctx, time.Second) {
			return NackRequeue
		}
	}
	log.Printf("event=report_unavailable err=%v decision=%s", last, NackRequeue)
	return NackRequeue
}

func sleep(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func supportsOperation(supported []string, operationType string) bool {
	for _, op := range supported {
		if op == operationType {
			return true
		}
	}
	return false
}

func sanitizeURI(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "invalid-uri"
	}
	parsed.User = nil
	parsed.RawQuery = ""
	parsed.Fragment = ""
	return parsed.String()
}
