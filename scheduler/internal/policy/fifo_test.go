package policy

import (
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

func TestFIFOSelectsOldestOperation(t *testing.T) {
	ten := time.Date(2026, 8, 25, 10, 0, 0, 0, time.UTC)
	eleven := time.Date(2026, 8, 25, 10, 1, 0, 0, time.UTC)
	got, ok := mustSelector(t, FIFO, Lexicographic).Select(model.Snapshot{
		Operations: []model.Operation{
			{OperationID: "b", Type: "METADATA", CreatedAt: eleven, OperationOrder: 0},
			{OperationID: "a", Type: "METADATA", CreatedAt: ten, OperationOrder: 0},
		},
		Workers: []model.Worker{available("worker-a", "METADATA")},
	})
	if !ok {
		t.Fatal("expected placement")
	}
	if got.OperationID != "a" || got.WorkerID != "worker-a" || got.OperationPolicy != FIFO {
		t.Fatalf("got %+v", got)
	}
}

func TestFIFOTieBreaksByOrderThenID(t *testing.T) {
	same := time.Date(2026, 8, 25, 11, 0, 0, 0, time.UTC)
	got, ok := mustSelector(t, FIFO, Lexicographic).Select(model.Snapshot{
		Operations: []model.Operation{
			{OperationID: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", Type: "THUMBNAIL", CreatedAt: same, OperationOrder: 1},
			{OperationID: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Type: "METADATA", CreatedAt: same, OperationOrder: 0},
		},
		Workers: []model.Worker{available("worker-a", "METADATA", "THUMBNAIL")},
	})
	if !ok || got.OperationID != "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestFIFOSameTimestampAndOrderUsesOperationID(t *testing.T) {
	same := time.Date(2026, 8, 25, 11, 0, 0, 0, time.UTC)
	got, ok := mustSelector(t, FIFO, Lexicographic).Select(model.Snapshot{
		Operations: []model.Operation{
			{OperationID: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", Type: "METADATA", CreatedAt: same, OperationOrder: 0},
			{OperationID: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Type: "METADATA", CreatedAt: same, OperationOrder: 0},
		},
		Workers: []model.Worker{available("worker-a", "METADATA")},
	})
	if !ok || got.OperationID != "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestFIFOIgnoresUnavailableWorkers(t *testing.T) {
	op := model.Operation{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}
	_, ok := mustSelector(t, FIFO, Lexicographic).Select(model.Snapshot{
		Operations: []model.Operation{op},
		Workers:    []model.Worker{{ID: "worker-a", Status: "UNAVAILABLE", SupportedOperations: []string{"METADATA"}}},
	})
	if ok {
		t.Fatal("unavailable worker should not be selected")
	}
}

func TestFIFOIgnoresWorkersMissingCapability(t *testing.T) {
	op := model.Operation{OperationID: "op-1", Type: "THUMBNAIL", CreatedAt: time.Now()}
	_, ok := mustSelector(t, FIFO, Lexicographic).Select(model.Snapshot{
		Operations: []model.Operation{op},
		Workers:    []model.Worker{available("worker-b", "METADATA")},
	})
	if ok {
		t.Fatal("metadata-only worker should not receive THUMBNAIL")
	}
}

func TestFIFONoEligibleWorkerReturnsNoPlacement(t *testing.T) {
	ops := []model.Operation{{OperationID: "op-1", Type: "THUMBNAIL", CreatedAt: time.Now()}}
	got, ok := mustSelector(t, FIFO, Lexicographic).Select(model.Snapshot{Operations: ops})
	if ok {
		t.Fatalf("unexpected placement %+v", got)
	}
}

func TestFIFODoesNotSkipOldestWhenItHasNoWorker(t *testing.T) {
	ten := time.Date(2026, 8, 25, 10, 0, 0, 0, time.UTC)
	eleven := time.Date(2026, 8, 25, 10, 1, 0, 0, time.UTC)
	got, ok := mustSelector(t, FIFO, RoundRobin).Select(model.Snapshot{
		Operations: []model.Operation{
			{OperationID: "thumb", Type: "THUMBNAIL", CreatedAt: ten},
			{OperationID: "meta", Type: "METADATA", CreatedAt: eleven},
		},
		Workers: []model.Worker{available("worker-a", "METADATA")},
	})
	if ok {
		t.Fatalf("strict FIFO must wait on oldest unschedulable op, got %+v", got)
	}
}

func TestFIFOChoosesLexicographicallyFirstEligibleWorker(t *testing.T) {
	op := model.Operation{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}
	got, ok := mustSelector(t, FIFO, Lexicographic).Select(model.Snapshot{
		Operations: []model.Operation{op},
		Workers: []model.Worker{
			available("worker-b", "METADATA"),
			available("worker-a", "METADATA"),
		},
	})
	if !ok || got.WorkerID != "worker-a" || got.WorkerPolicy != Lexicographic {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestNewRejectsUnimplementedPolicies(t *testing.T) {
	if _, err := New("ROUND_ROBIN", Lexicographic); err == nil {
		t.Fatal("expected operation policy error")
	}
	if _, err := New(FIFO, "SJF"); err == nil {
		t.Fatal("expected worker policy error")
	}
	got, err := New(FIFO, LeastLoaded)
	if err != nil || got.Name() != "FIFO+LEAST_LOADED" {
		t.Fatalf("got=%v err=%v", got, err)
	}
	rr, err := New(FIFO, RoundRobin)
	if err != nil || rr.Name() != "FIFO+ROUND_ROBIN" {
		t.Fatalf("got=%v err=%v", rr, err)
	}
}

func mustSelector(t *testing.T, operation, worker string) Selector {
	t.Helper()
	got, err := New(operation, worker)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

func available(id string, ops ...string) model.Worker {
	return model.Worker{ID: id, Status: "AVAILABLE", SupportedOperations: ops}
}
