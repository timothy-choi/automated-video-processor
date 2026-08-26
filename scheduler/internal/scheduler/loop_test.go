package scheduler

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/client"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/policy"
)

func TestTickAssignsLexicographicPlacement(t *testing.T) {
	ctrl := &fakeControl{
		snapshot: model.Snapshot{
			Operations: []model.Operation{
				{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Date(2026, 8, 25, 10, 0, 0, 0, time.UTC)},
			},
			Workers: []model.Worker{
				{ID: "worker-b", Status: "AVAILABLE", SupportedOperations: []string{"METADATA"}},
				{ID: "worker-a", Status: "AVAILABLE", SupportedOperations: []string{"METADATA"}},
			},
		},
		assign: model.AssignResponse{DecisionID: "dec-1", OperationID: "op-1", WorkerID: "worker-a", OperationPolicy: "FIFO", WorkerPolicy: "LEXICOGRAPHIC"},
	}
	loop := &Loop{Client: ctrl, Selector: mustSelector(t, policy.FIFO, policy.Lexicographic)}
	if wait := loop.tick(context.Background()); wait != 0 {
		t.Fatalf("wait=%s", wait)
	}
	if ctrl.assignCalls != 1 || ctrl.last.WorkerID != "worker-a" || ctrl.last.WorkerPolicy != policy.Lexicographic {
		t.Fatalf("assignCalls=%d last=%+v", ctrl.assignCalls, ctrl.last)
	}
}

func TestTickAssignsRoundRobinFromCursor(t *testing.T) {
	ctrl := &fakeControl{
		snapshot: model.Snapshot{
			Operations: []model.Operation{
				{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()},
			},
			RoundRobinCursors: map[string]string{"METADATA": "worker-a"},
			Workers: []model.Worker{
				{ID: "worker-a", Status: "AVAILABLE", SupportedOperations: []string{"METADATA"}},
				{ID: "worker-b", Status: "AVAILABLE", SupportedOperations: []string{"METADATA"}},
			},
		},
		assign: model.AssignResponse{DecisionID: "dec-2", OperationID: "op-1", WorkerID: "worker-b", OperationPolicy: "FIFO", WorkerPolicy: "ROUND_ROBIN"},
	}
	loop := &Loop{Client: ctrl, Selector: mustSelector(t, policy.FIFO, policy.RoundRobin)}
	if wait := loop.tick(context.Background()); wait != 0 {
		t.Fatalf("wait=%s", wait)
	}
	if ctrl.last.WorkerID != "worker-b" || ctrl.last.WorkerPolicy != policy.RoundRobin {
		t.Fatalf("last=%+v", ctrl.last)
	}
}

func TestTickConflictDoesNotCrash(t *testing.T) {
	ctrl := &fakeControl{
		snapshot: model.Snapshot{
			Operations: []model.Operation{{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}},
			Workers:    []model.Worker{{ID: "worker-a", Status: "AVAILABLE", SupportedOperations: []string{"METADATA"}}},
		},
		assignErr: &client.StatusError{Status: http.StatusConflict, Body: `{"code":"INVALID_OPERATION_STATE"}`},
	}
	loop := &Loop{Client: ctrl, Selector: mustSelector(t, policy.FIFO, policy.Lexicographic), PollInterval: time.Second}
	if wait := loop.tick(context.Background()); wait != 0 {
		t.Fatalf("conflict should retry immediately, wait=%s", wait)
	}
	if ctrl.assignCalls != 1 {
		t.Fatalf("assignCalls=%d", ctrl.assignCalls)
	}
}

func TestTickNoEligibleWorkerSleeps(t *testing.T) {
	ctrl := &fakeControl{
		snapshot: model.Snapshot{
			Operations: []model.Operation{{OperationID: "op-1", Type: "THUMBNAIL", CreatedAt: time.Now()}},
			Workers:    []model.Worker{{ID: "worker-a", Status: "AVAILABLE", SupportedOperations: []string{"METADATA"}}},
		},
	}
	loop := &Loop{Client: ctrl, Selector: mustSelector(t, policy.FIFO, policy.RoundRobin), PollInterval: 200 * time.Millisecond}
	if wait := loop.tick(context.Background()); wait != 200*time.Millisecond {
		t.Fatalf("wait=%s", wait)
	}
	if ctrl.assignCalls != 0 {
		t.Fatalf("assignCalls=%d", ctrl.assignCalls)
	}
}

func TestRunStopsOnCancelAfterConflict(t *testing.T) {
	ctrl := &fakeControl{
		snapshot: model.Snapshot{
			Operations: []model.Operation{{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}},
			Workers:    []model.Worker{{ID: "worker-a", Status: "AVAILABLE", SupportedOperations: []string{"METADATA"}}},
		},
		assignErr: &client.StatusError{Status: http.StatusConflict, Body: "ALREADY_ASSIGNED"},
	}
	ctx, cancel := context.WithCancel(context.Background())
	loop := &Loop{Client: ctrl, Selector: mustSelector(t, policy.FIFO, policy.Lexicographic)}
	go func() {
		time.Sleep(20 * time.Millisecond)
		cancel()
	}()
	err := loop.Run(ctx)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err=%v", err)
	}
}

func mustSelector(t *testing.T, operation, worker string) policy.Selector {
	t.Helper()
	got, err := policy.New(operation, worker)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

type fakeControl struct {
	snapshot    model.Snapshot
	assign      model.AssignResponse
	assignErr   error
	assignCalls int
	last        model.Placement
}

func (f *fakeControl) Snapshot(ctx context.Context) (model.Snapshot, error) {
	return f.snapshot, nil
}

func (f *fakeControl) Assign(ctx context.Context, placement model.Placement) (model.AssignResponse, error) {
	f.assignCalls++
	f.last = placement
	if f.assignErr != nil {
		return model.AssignResponse{}, f.assignErr
	}
	return f.assign, nil
}
