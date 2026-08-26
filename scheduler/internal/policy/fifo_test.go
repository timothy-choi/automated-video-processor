package policy

import (
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

func TestFIFOSelectsOldestOperation(t *testing.T) {
	ten := time.Date(2026, 8, 25, 10, 0, 0, 0, time.UTC)
	eleven := time.Date(2026, 8, 25, 10, 1, 0, 0, time.UTC)
	got, ok := FIFOPolicy{}.Select([]model.Operation{
		{OperationID: "b", Type: "METADATA", CreatedAt: eleven, OperationOrder: 0},
		{OperationID: "a", Type: "METADATA", CreatedAt: ten, OperationOrder: 0},
	}, []model.Worker{
		available("worker-a", "METADATA"),
	})
	if !ok {
		t.Fatal("expected placement")
	}
	if got.OperationID != "a" || got.WorkerID != "worker-a" || got.Policy != FIFO {
		t.Fatalf("got %+v", got)
	}
}

func TestFIFOTieBreaksByOrderThenID(t *testing.T) {
	same := time.Date(2026, 8, 25, 11, 0, 0, 0, time.UTC)
	got, ok := FIFOPolicy{}.Select([]model.Operation{
		{OperationID: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", Type: "THUMBNAIL", CreatedAt: same, OperationOrder: 1},
		{OperationID: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Type: "METADATA", CreatedAt: same, OperationOrder: 0},
	}, []model.Worker{
		available("worker-a", "METADATA", "THUMBNAIL"),
	})
	if !ok || got.OperationID != "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestFIFOSameTimestampAndOrderUsesOperationID(t *testing.T) {
	same := time.Date(2026, 8, 25, 11, 0, 0, 0, time.UTC)
	got, ok := FIFOPolicy{}.Select([]model.Operation{
		{OperationID: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", Type: "METADATA", CreatedAt: same, OperationOrder: 0},
		{OperationID: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Type: "METADATA", CreatedAt: same, OperationOrder: 0},
	}, []model.Worker{
		available("worker-a", "METADATA"),
	})
	if !ok || got.OperationID != "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestFIFOIgnoresUnavailableWorkers(t *testing.T) {
	op := model.Operation{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}
	_, ok := FIFOPolicy{}.Select([]model.Operation{op}, []model.Worker{
		{ID: "worker-a", Status: "UNAVAILABLE", SupportedOperations: []string{"METADATA"}},
	})
	if ok {
		t.Fatal("unavailable worker should not be selected")
	}
}

func TestFIFOIgnoresWorkersMissingCapability(t *testing.T) {
	op := model.Operation{OperationID: "op-1", Type: "THUMBNAIL", CreatedAt: time.Now()}
	_, ok := FIFOPolicy{}.Select([]model.Operation{op}, []model.Worker{
		available("worker-b", "METADATA"),
	})
	if ok {
		t.Fatal("metadata-only worker should not receive THUMBNAIL")
	}
}

func TestFIFONoEligibleWorkerReturnsNoPlacement(t *testing.T) {
	ops := []model.Operation{{OperationID: "op-1", Type: "THUMBNAIL", CreatedAt: time.Now()}}
	got, ok := FIFOPolicy{}.Select(ops, nil)
	if ok {
		t.Fatalf("unexpected placement %+v", got)
	}
}

func TestFIFODoesNotSkipOldestWhenItHasNoWorker(t *testing.T) {
	ten := time.Date(2026, 8, 25, 10, 0, 0, 0, time.UTC)
	eleven := time.Date(2026, 8, 25, 10, 1, 0, 0, time.UTC)
	got, ok := FIFOPolicy{}.Select([]model.Operation{
		{OperationID: "thumb", Type: "THUMBNAIL", CreatedAt: ten},
		{OperationID: "meta", Type: "METADATA", CreatedAt: eleven},
	}, []model.Worker{
		available("worker-a", "METADATA"),
	})
	if ok {
		t.Fatalf("strict FIFO must wait on oldest unschedulable op, got %+v", got)
	}
}

func TestFIFOChoosesLexicographicallyFirstEligibleWorker(t *testing.T) {
	op := model.Operation{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}
	got, ok := FIFOPolicy{}.Select([]model.Operation{op}, []model.Worker{
		available("worker-b", "METADATA"),
		available("worker-a", "METADATA"),
	})
	if !ok || got.WorkerID != "worker-a" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestNewRejectsUnimplementedPolicies(t *testing.T) {
	if _, err := New("ROUND_ROBIN"); err == nil {
		t.Fatal("expected error")
	}
	got, err := New("FIFO")
	if err != nil || got.Name() != FIFO {
		t.Fatalf("got=%v err=%v", got, err)
	}
}

func available(id string, ops ...string) model.Worker {
	return model.Worker{ID: id, Status: "AVAILABLE", SupportedOperations: ops}
}
