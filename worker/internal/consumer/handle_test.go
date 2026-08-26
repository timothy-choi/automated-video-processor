package consumer

import (
	"context"
	"errors"
	"net/http"
	"strings"
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
	start              model.StartResponse
	startErr           error
	completeErrs       []error
	failErrs           []error
	renewErrs          []error
	completes          int
	fails              int
	starts             int
	renews             int
	lastStartWorkerID  string
	lastComplete       model.CompleteRequest
	lastFailAttemptID  string
}

func (f *fakeControl) Start(ctx context.Context, operationID, workerID string) (model.StartResponse, error) {
	f.starts++
	f.lastStartWorkerID = workerID
	if f.startErr != nil {
		return model.StartResponse{}, f.startErr
	}
	return f.start, nil
}

func (f *fakeControl) Complete(ctx context.Context, operationID string, request model.CompleteRequest) error {
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
	f.renews++
	if len(f.renewErrs) > 0 {
		err := f.renewErrs[0]
		f.renewErrs = f.renewErrs[1:]
		return model.RenewResponse{}, err
	}
	return model.RenewResponse{AttemptID: attemptID, WorkerID: workerID, Status: "RUNNING"}, nil
}
