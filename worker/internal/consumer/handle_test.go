package consumer

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/client"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/run"
)

const validAssignment = `{
	"schemaVersion": 1,
	"operationId": "11111111-1111-1111-1111-111111111111",
	"jobId": "22222222-2222-2222-2222-222222222222",
	"type": "METADATA",
	"inputUri": "s3://media-input/sample.mp4",
	"dispatchedAt": "2026-08-25T02:00:00Z"
}`

func TestHandleAcksAfterSuccessfulComplete(t *testing.T) {
	ctrl := &fakeControl{start: startedOK()}
	executed := 0
	decision := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		executed++
		if claimed.OperationID != "11111111-1111-1111-1111-111111111111" {
			t.Fatalf("operation=%s", claimed.OperationID)
		}
		format := "mp4"
		return run.Result{RuntimeMs: 12, Metadata: &model.MetadataResult{FormatName: &format}}, nil
	})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	if executed != 1 || ctrl.completes != 1 || ctrl.fails != 0 {
		t.Fatalf("executed=%d completes=%d fails=%d", executed, ctrl.completes, ctrl.fails)
	}
	if ctrl.lastStartWorkerID != "worker-a" {
		t.Fatalf("start workerId=%s", ctrl.lastStartWorkerID)
	}
	if ctrl.lastStartAssignmentID != "" {
		t.Fatalf("v1 start assignmentId=%s", ctrl.lastStartAssignmentID)
	}
	if ctrl.lastComplete.AttemptID != "attempt-1" {
		t.Fatalf("complete attemptId=%s", ctrl.lastComplete.AttemptID)
	}
}

func TestHandleNacksWhenCompleteUnavailable(t *testing.T) {
	ctrl := &fakeControl{
		start:        startedOK(),
		completeErrs: []error{errors.New("connection refused"), errors.New("connection refused"), errors.New("connection refused")},
	}
	decision := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Result{RuntimeMs: 5}, nil
	})
	if decision != NackRequeue {
		t.Fatalf("decision=%s", decision)
	}
}

func TestHandleAcksFailedMediaAfterPersistingFailure(t *testing.T) {
	ctrl := &fakeControl{start: startedOK()}
	decision := Handle(context.Background(), "worker-b", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Result{RuntimeMs: 8}, errors.New("ffprobe failed")
	})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.fails != 1 || ctrl.completes != 0 {
		t.Fatalf("fails=%d completes=%d", ctrl.fails, ctrl.completes)
	}
	if ctrl.lastFailAttemptID != "attempt-1" {
		t.Fatalf("fail attemptId=%s", ctrl.lastFailAttemptID)
	}
}

func TestHandleNacksWhenFailReportUnavailable(t *testing.T) {
	ctrl := &fakeControl{
		start:    startedOK(),
		failErrs: []error{errors.New("control down"), errors.New("control down"), errors.New("control down")},
	}
	decision := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Result{RuntimeMs: 3}, errors.New("object not found")
	})
	if decision != NackRequeue {
		t.Fatalf("decision=%s", decision)
	}
}

func TestHandleAcksStaleCompletionConflict(t *testing.T) {
	ctrl := &fakeControl{
		start:        startedOK(),
		completeErrs: []error{&client.StatusError{Status: http.StatusConflict, Body: `{"code":"STALE_EXECUTION_ATTEMPT"}`}},
	}
	decision := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Result{RuntimeMs: 4}, nil
	})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
}

func TestHandleDoesNotExecuteDuplicateOrTerminal(t *testing.T) {
	for _, outcome := range []string{model.StartAlreadyRunning, model.StartAlreadyTerminal} {
		ctrl := &fakeControl{start: model.StartResponse{Outcome: outcome, Status: "COMPLETED"}}
		executed := 0
		decision := Handle(context.Background(), "worker-c", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
			executed++
			return run.Result{}, nil
		})
		if decision != Ack {
			t.Fatalf("outcome=%s decision=%s", outcome, decision)
		}
		if executed != 0 {
			t.Fatalf("outcome=%s executed duplicate work", outcome)
		}
		if ctrl.renews != 0 {
			t.Fatalf("outcome=%s renews=%d", outcome, ctrl.renews)
		}
	}
}

func TestHandleDropsWhenStartedWithoutAttemptID(t *testing.T) {
	ctrl := &fakeControl{start: model.StartResponse{Outcome: model.StartStarted, Status: "RUNNING"}}
	if got := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, unexpectedExec(t)); got != NackDrop {
		t.Fatalf("decision=%s", got)
	}
}

func TestHandleDropsWorkerIDMismatchWithoutExecuting(t *testing.T) {
	body := []byte(`{
		"schemaVersion": 3,
		"operationId": "11111111-1111-1111-1111-111111111111",
		"jobId": "22222222-2222-2222-2222-222222222222",
		"type": "METADATA",
		"inputUri": "s3://media-input/sample.mp4",
		"workerId": "worker-a",
		"scheduledAt": "2026-08-25T18:00:00Z",
		"policy": "FIFO",
		"assignmentId": "33333333-3333-3333-3333-333333333333"
	}`)
	ctrl := &fakeControl{start: startedOK()}
	decision := Handle(context.Background(), "worker-b", body, ctrl, unexpectedExec(t))
	if decision != NackDrop {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.starts != 0 {
		t.Fatalf("starts=%d", ctrl.starts)
	}
}

func TestHandleAcceptsMatchingV3AssignmentAndSendsAssignmentID(t *testing.T) {
	body := []byte(`{
		"schemaVersion": 3,
		"operationId": "11111111-1111-1111-1111-111111111111",
		"jobId": "22222222-2222-2222-2222-222222222222",
		"type": "METADATA",
		"inputUri": "s3://media-input/sample.mp4",
		"workerId": "worker-a",
		"scheduledAt": "2026-08-25T18:00:00Z",
		"policy": "FIFO",
		"assignmentId": "33333333-3333-3333-3333-333333333333"
	}`)
	ctrl := &fakeControl{start: startedOK()}
	decision := Handle(context.Background(), "worker-a", body, ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		return run.Result{RuntimeMs: 1}, nil
	})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.completes != 1 {
		t.Fatalf("completes=%d", ctrl.completes)
	}
	if ctrl.lastStartAssignmentID != "33333333-3333-3333-3333-333333333333" {
		t.Fatalf("start assignmentId=%s", ctrl.lastStartAssignmentID)
	}
}

func TestHandleDropsObsoleteV2WithoutExecuting(t *testing.T) {
	body := []byte(`{
		"schemaVersion": 2,
		"operationId": "11111111-1111-1111-1111-111111111111",
		"jobId": "22222222-2222-2222-2222-222222222222",
		"type": "METADATA",
		"inputUri": "s3://media-input/sample.mp4",
		"workerId": "worker-a",
		"scheduledAt": "2026-08-25T18:00:00Z",
		"policy": "FIFO"
	}`)
	ctrl := &fakeControl{start: startedOK()}
	decision := Handle(context.Background(), "worker-a", body, ctrl, unexpectedExec(t))
	if decision != NackDrop {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.starts != 0 {
		t.Fatalf("starts=%d", ctrl.starts)
	}
}

func TestHandleDropsStaleAssignmentStartWithoutExecuting(t *testing.T) {
	body := []byte(`{
		"schemaVersion": 3,
		"operationId": "11111111-1111-1111-1111-111111111111",
		"jobId": "22222222-2222-2222-2222-222222222222",
		"type": "METADATA",
		"inputUri": "s3://media-input/sample.mp4",
		"workerId": "worker-a",
		"scheduledAt": "2026-08-25T18:00:00Z",
		"policy": "FIFO",
		"assignmentId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	}`)
	ctrl := &fakeControl{startErr: &client.StatusError{Status: http.StatusConflict, Body: `{"code":"STALE_ASSIGNMENT"}`}}
	decision := Handle(context.Background(), "worker-a", body, ctrl, unexpectedExec(t))
	if decision != NackDrop {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.starts != 1 {
		t.Fatalf("starts=%d", ctrl.starts)
	}
	if ctrl.lastStartAssignmentID != "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" {
		t.Fatalf("assignmentId=%s", ctrl.lastStartAssignmentID)
	}
}

func TestHandleDropsMalformedAndInvalidState(t *testing.T) {
	ctrl := &fakeControl{start: model.StartResponse{Outcome: model.StartInvalidState, Status: "QUEUED"}}
	if got := Handle(context.Background(), "worker-a", []byte("{nope"), ctrl, unexpectedExec(t)); got != NackDrop {
		t.Fatalf("malformed decision=%s", got)
	}
	if got := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, unexpectedExec(t)); got != NackDrop {
		t.Fatalf("invalid state decision=%s", got)
	}
}

func TestHandleDropsCapabilityMismatchWithoutExecuting(t *testing.T) {
	body := []byte(`{
		"schemaVersion": 1,
		"operationId": "11111111-1111-1111-1111-111111111111",
		"jobId": "22222222-2222-2222-2222-222222222222",
		"type": "TRANSCODE_1080P",
		"inputUri": "s3://media-input/sample.mp4",
		"dispatchedAt": "2026-08-25T02:00:00Z"
	}`)
	ctrl := &fakeControl{start: startedOK()}
	decision := HandleWithCapabilities(context.Background(), "worker-a", []string{"METADATA", "THUMBNAIL"}, body, ctrl, unexpectedExec(t))
	if decision != NackDrop {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.completes != 0 || ctrl.fails != 0 || ctrl.starts != 0 {
		t.Fatalf("completes=%d fails=%d starts=%d", ctrl.completes, ctrl.fails, ctrl.starts)
	}
}

func TestHandleRequeuesWhenStartUnavailable(t *testing.T) {
	ctrl := &fakeControl{startErr: errors.New("dial tcp: connection refused")}
	if got := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, unexpectedExec(t)); got != NackRequeue {
		t.Fatalf("decision=%s", got)
	}
}

func TestHandleDropsWhenOperationMissing(t *testing.T) {
	ctrl := &fakeControl{startErr: &client.StatusError{Status: 404, Body: "OPERATION_NOT_FOUND"}}
	if got := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, unexpectedExec(t)); got != NackDrop {
		t.Fatalf("decision=%s", got)
	}
}

func TestHandleRenewsDuringExecutionAndStopsAfterComplete(t *testing.T) {
	ctrl := &fakeControl{start: startedOK()}
	decision := HandleWithOptions(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		time.Sleep(50 * time.Millisecond)
		return run.Result{RuntimeMs: 2}, nil
	}, Options{RenewInterval: 15 * time.Millisecond})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.renews < 1 {
		t.Fatalf("renews=%d", ctrl.renews)
	}
	after := ctrl.renews
	time.Sleep(40 * time.Millisecond)
	if ctrl.renews != after {
		t.Fatalf("renew continued after complete: before=%d after=%d", after, ctrl.renews)
	}
}

func TestHandleTransientRenewFailureDoesNotKillWork(t *testing.T) {
	ctrl := &fakeControl{start: startedOK(), renewErrs: []error{errors.New("temporary")}}
	decision := HandleWithOptions(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		time.Sleep(50 * time.Millisecond)
		return run.Result{RuntimeMs: 2}, nil
	}, Options{RenewInterval: 15 * time.Millisecond})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.completes != 1 {
		t.Fatalf("completes=%d", ctrl.completes)
	}
}

func TestWorkerIDConfig(t *testing.T) {
	if !strings.Contains(Ack.String(), "ack") {
		t.Fatal(Ack)
	}
}

func TestHandleStopsExecutorWhenRenewRequestsCancel(t *testing.T) {
	ctrl := &fakeControl{start: startedOK(), cancelOnRenew: 1}
	started := make(chan struct{})
	decision := HandleWithOptions(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		close(started)
		<-ctx.Done()
		return run.Result{RuntimeMs: 9}, ctx.Err()
	}, Options{RenewInterval: 5 * time.Millisecond})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	select {
	case <-started:
	default:
		t.Fatal("executor should have started")
	}
	if ctrl.cancelled != 1 || ctrl.completes != 0 || ctrl.fails != 0 {
		t.Fatalf("cancelled=%d completes=%d fails=%d", ctrl.cancelled, ctrl.completes, ctrl.fails)
	}
	if ctrl.lastCancelledAttemptID != "attempt-1" {
		t.Fatalf("cancelled attempt=%s", ctrl.lastCancelledAttemptID)
	}
}

func TestHandleDoesNotCompleteWhenCancelWinsTheRaceWithFinishedWork(t *testing.T) {
	ctrl := &fakeControl{start: startedOK(), cancelOnRenew: 1}
	decision := HandleWithOptions(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		<-ctx.Done()
		format := "mp4"
		return run.Result{
			RuntimeMs: 4,
			Metadata:  &model.MetadataResult{FormatName: &format},
		}, nil
	}, Options{RenewInterval: 5 * time.Millisecond})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.cancelled != 1 || ctrl.completes != 0 {
		t.Fatalf("cancelled=%d completes=%d", ctrl.cancelled, ctrl.completes)
	}
}

func TestHandleAcksStaleCancellationConflict(t *testing.T) {
	ctrl := &fakeControl{
		start:         startedOK(),
		cancelOnRenew: 1,
		cancelledErrs: []error{&client.StatusError{Status: http.StatusConflict, Body: `{"code":"STALE_EXECUTION_ATTEMPT"}`}},
	}
	decision := HandleWithOptions(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		<-ctx.Done()
		return run.Result{RuntimeMs: 3}, ctx.Err()
	}, Options{RenewInterval: 5 * time.Millisecond})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.cancelled != 1 || ctrl.completes != 0 {
		t.Fatalf("cancelled=%d completes=%d", ctrl.cancelled, ctrl.completes)
	}
}

func TestHandleNacksWhenCancelledReportUnavailable(t *testing.T) {
	ctrl := &fakeControl{
		start:         startedOK(),
		cancelOnRenew: 1,
		cancelledErrs: []error{errors.New("control down"), errors.New("control down"), errors.New("control down")},
	}
	decision := HandleWithOptions(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		<-ctx.Done()
		return run.Result{RuntimeMs: 2}, ctx.Err()
	}, Options{RenewInterval: 5 * time.Millisecond})
	if decision != NackRequeue {
		t.Fatalf("decision=%s", decision)
	}
}

func TestHandleStopsOnStaleRenewConflictWithoutCompleting(t *testing.T) {
	ctrl := &fakeControl{
		start:     startedOK(),
		renewErrs: []error{&client.StatusError{Status: http.StatusConflict, Body: `{"code":"STALE_EXECUTION_ATTEMPT"}`}},
	}
	decision := HandleWithOptions(context.Background(), "worker-a", []byte(validAssignment), ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		<-ctx.Done()
		return run.Result{RuntimeMs: 6}, ctx.Err()
	}, Options{RenewInterval: 5 * time.Millisecond})
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.cancelled != 1 || ctrl.completes != 0 || ctrl.fails != 0 {
		t.Fatalf("cancelled=%d completes=%d fails=%d", ctrl.cancelled, ctrl.completes, ctrl.fails)
	}
}

func TestHandleAcksCancelledTerminalStartWithoutExecuting(t *testing.T) {
	ctrl := &fakeControl{start: model.StartResponse{Outcome: model.StartAlreadyTerminal, Status: "CANCELLED"}}
	decision := Handle(context.Background(), "worker-a", []byte(validAssignment), ctrl, unexpectedExec(t))
	if decision != Ack {
		t.Fatalf("decision=%s", decision)
	}
	if ctrl.cancelled != 0 || ctrl.completes != 0 {
		t.Fatalf("cancelled=%d completes=%d", ctrl.cancelled, ctrl.completes)
	}
}

func TestHandleCancelsEachOperationType(t *testing.T) {
	types := []string{"METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P", "H264_TO_AV1"}
	for _, opType := range types {
		t.Run(opType, func(t *testing.T) {
			body := []byte(`{
				"schemaVersion": 1,
				"operationId": "11111111-1111-1111-1111-111111111111",
				"jobId": "22222222-2222-2222-2222-222222222222",
				"type": "` + opType + `",
				"inputUri": "s3://media-input/sample.mp4",
				"dispatchedAt": "2026-08-25T02:00:00Z"
			}`)
			ctrl := &fakeControl{start: startedOK(), cancelOnRenew: 1}
			sawType := ""
			decision := HandleWithOptions(context.Background(), "worker-a", body, ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
				sawType = claimed.Type
				<-ctx.Done()
				return run.Result{RuntimeMs: 1}, ctx.Err()
			}, Options{RenewInterval: 5 * time.Millisecond})
			if decision != Ack {
				t.Fatalf("decision=%s", decision)
			}
			if sawType != opType {
				t.Fatalf("type=%s", sawType)
			}
			if ctrl.cancelled != 1 || ctrl.completes != 0 {
				t.Fatalf("cancelled=%d completes=%d", ctrl.cancelled, ctrl.completes)
			}
		})
	}
}

func TestHandleShutdownStillNacksWithoutUserCancel(t *testing.T) {
	ctrl := &fakeControl{start: startedOK()}
	ctx, cancel := context.WithCancel(context.Background())
	started := make(chan struct{})
	done := make(chan Decision, 1)
	go func() {
		done <- Handle(ctx, "worker-a", []byte(validAssignment), ctrl, func(execCtx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
			close(started)
			<-execCtx.Done()
			return run.Result{RuntimeMs: 1}, execCtx.Err()
		})
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("executor did not start")
	}
	cancel()
	select {
	case decision := <-done:
		if decision != NackRequeue {
			t.Fatalf("decision=%s", decision)
		}
	case <-time.After(time.Second):
		t.Fatal("handle did not return")
	}
	if ctrl.cancelled != 0 || ctrl.completes != 0 || ctrl.fails != 0 {
		t.Fatalf("cancelled=%d completes=%d fails=%d", ctrl.cancelled, ctrl.completes, ctrl.fails)
	}
}

func unexpectedExec(t *testing.T) Executor {
	t.Helper()
	return func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
		t.Fatal("executor should not run")
		return run.Result{}, nil
	}
}

func startedOK() model.StartResponse {
	return model.StartResponse{Outcome: model.StartStarted, Status: "RUNNING", AttemptID: "attempt-1", WorkerID: "worker-a"}
}

type fakeControl struct {
	mu                     sync.Mutex
	start                  model.StartResponse
	startErr               error
	completeErrs           []error
	failErrs               []error
	renewErrs              []error
	cancelledErrs          []error
	cancelOnRenew          int
	completes              int
	fails                  int
	starts                 int
	renews                 int
	cancelled              int
	lastStartWorkerID      string
	lastStartAssignmentID  string
	lastComplete           model.CompleteRequest
	lastFailAttemptID      string
	lastCancelledAttemptID string
	lastCancelledRuntimeMs int64
}

func (f *fakeControl) Start(ctx context.Context, operationID, workerID, assignmentID string) (model.StartResponse, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.starts++
	f.lastStartWorkerID = workerID
	f.lastStartAssignmentID = assignmentID
	if f.startErr != nil {
		return model.StartResponse{}, f.startErr
	}
	return f.start, nil
}

func (f *fakeControl) Complete(ctx context.Context, operationID string, request model.CompleteRequest) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.completes++
	f.lastComplete = request
	if len(f.completeErrs) > 0 {
		err := f.completeErrs[0]
		f.completeErrs = f.completeErrs[1:]
		return err
	}
	return nil
}

func (f *fakeControl) Fail(ctx context.Context, operationID string, runtimeMs *int64, reason, attemptID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.fails++
	f.lastFailAttemptID = attemptID
	if len(f.failErrs) > 0 {
		err := f.failErrs[0]
		f.failErrs = f.failErrs[1:]
		return err
	}
	return nil
}

func (f *fakeControl) Renew(ctx context.Context, operationID, attemptID, workerID string) (model.RenewResponse, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.renews++
	if len(f.renewErrs) > 0 {
		err := f.renewErrs[0]
		f.renewErrs = f.renewErrs[1:]
		return model.RenewResponse{}, err
	}
	resp := model.RenewResponse{AttemptID: attemptID, WorkerID: workerID, Status: "RUNNING"}
	if f.cancelOnRenew > 0 && f.renews >= f.cancelOnRenew {
		resp.CancelRequested = true
	}
	return resp, nil
}

func (f *fakeControl) Cancelled(ctx context.Context, operationID, attemptID, workerID string, runtimeMs int64) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.cancelled++
	f.lastCancelledAttemptID = attemptID
	f.lastCancelledRuntimeMs = runtimeMs
	if len(f.cancelledErrs) > 0 {
		err := f.cancelledErrs[0]
		f.cancelledErrs = f.cancelledErrs[1:]
		return err
	}
	return nil
}
