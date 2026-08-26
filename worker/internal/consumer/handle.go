package consumer

import (
	"context"
	"log"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/assignment"
	"github.com/timothy-choi/automated-video-processor/worker/internal/client"
	"github.com/timothy-choi/automated-video-processor/worker/internal/lease"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/run"
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
	Start(ctx context.Context, operationID, workerID string) (model.StartResponse, error)
	Complete(ctx context.Context, operationID string, request model.CompleteRequest) error
	Fail(ctx context.Context, operationID string, runtimeMs *int64, reason, attemptID string) error
	Renew(ctx context.Context, operationID, attemptID, workerID string) (model.RenewResponse, error)
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
	parsed, err := assignment.Parse(body)
	if err != nil {
		log.Printf("worker=%s event=malformed_message err=%v decision=%s", workerID, err, NackDrop)
		return NackDrop
	}
	supported := opts.Supported
	if supported == nil {
		supported = []string{"METADATA", "THUMBNAIL"}
	}
	if !supportsOperation(supported, parsed.Type) {
		log.Printf(
			"worker=%s job=%s operation=%s type=%s event=capability_mismatch decision=%s",
			workerID, parsed.JobID, parsed.OperationID, parsed.Type, NackDrop,
		)
		return NackDrop
	}
	log.Printf(
		"worker=%s job=%s operation=%s type=%s event=received input=%s",
		workerID, parsed.JobID, parsed.OperationID, parsed.Type, parsed.InputURI,
	)

	start, err := ctrl.Start(ctx, parsed.OperationID, workerID)
	if err != nil {
		if client.IsUnavailable(err) {
			log.Printf(
				"worker=%s job=%s operation=%s type=%s event=start_unavailable err=%v decision=%s",
				workerID, parsed.JobID, parsed.OperationID, parsed.Type, err, NackRequeue,
			)
			return NackRequeue
		}
		log.Printf(
			"worker=%s job=%s operation=%s type=%s event=start_rejected err=%v decision=%s",
			workerID, parsed.JobID, parsed.OperationID, parsed.Type, err, NackDrop,
		)
		return NackDrop
	}

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
	renewCtx, stopRenew := context.WithCancel(ctx)
	defer stopRenew()
	go lease.RunLoop(renewCtx, renewInterval, func(renewCallCtx context.Context) error {
		_, err := ctrl.Renew(renewCallCtx, parsed.OperationID, start.AttemptID, workerID)
		return err
	})

	log.Printf(
		"worker=%s job=%s operation=%s type=%s attempt=%s event=execution_start",
		workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.AttemptID,
	)
	result, execErr := exec(ctx, parsed.Claimed())
	stopRenew()
	if execErr != nil {
		decision := reportWithRetry(ctx, func(reportCtx context.Context) error {
			return ctrl.Fail(reportCtx, parsed.OperationID, &result.RuntimeMs, execErr.Error(), start.AttemptID)
		})
		log.Printf(
			"worker=%s job=%s operation=%s type=%s attempt=%s event=execution_failure runtime_ms=%d err=%v decision=%s",
			workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.AttemptID, result.RuntimeMs, execErr, decision,
		)
		return decision
	}

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
	log.Printf(
		"worker=%s job=%s operation=%s type=%s attempt=%s event=execution_completed runtime_ms=%d decision=%s",
		workerID, parsed.JobID, parsed.OperationID, parsed.Type, start.AttemptID, result.RuntimeMs, decision,
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
