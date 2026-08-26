package policy

import (
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

func TestRoundRobinRotatesAndWraps(t *testing.T) {
	workers := []string{"worker-a", "worker-b", "worker-c"}
	if got := nextRoundRobin(workers, ""); got != "worker-a" {
		t.Fatalf("got %s", got)
	}
	if got := nextRoundRobin(workers, "worker-a"); got != "worker-b" {
		t.Fatalf("got %s", got)
	}
	if got := nextRoundRobin(workers, "worker-b"); got != "worker-c" {
		t.Fatalf("got %s", got)
	}
	if got := nextRoundRobin(workers, "worker-c"); got != "worker-a" {
		t.Fatalf("got %s", got)
	}
}

func TestRoundRobinSkipsMissingLastWorker(t *testing.T) {
	if got := nextRoundRobin([]string{"worker-a", "worker-c"}, "worker-b"); got != "worker-c" {
		t.Fatalf("got %s", got)
	}
}

func TestRoundRobinCapabilityFilter(t *testing.T) {
	sel := mustSelector(t, FIFO, RoundRobin)
	snap := model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "THUMBNAIL", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			available("worker-a", "METADATA", "THUMBNAIL"),
			available("worker-b", "METADATA"),
			available("worker-c", "METADATA", "THUMBNAIL"),
		},
	}
	first, ok := sel.Select(snap)
	if !ok || first.WorkerID != "worker-a" {
		t.Fatalf("got %+v ok=%t", first, ok)
	}
	snap.RoundRobinCursors = map[string]string{"THUMBNAIL": "worker-a"}
	second, ok := sel.Select(snap)
	if !ok || second.WorkerID != "worker-c" {
		t.Fatalf("got %+v ok=%t", second, ok)
	}
}

func TestRoundRobinIgnoresUnavailable(t *testing.T) {
	sel := mustSelector(t, FIFO, RoundRobin)
	snap := model.Snapshot{
		Operations:        []model.Operation{{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}},
		RoundRobinCursors: map[string]string{"METADATA": "worker-a"},
		Workers: []model.Worker{
			available("worker-a", "METADATA"),
			{ID: "worker-b", Status: "UNAVAILABLE", SupportedOperations: []string{"METADATA"}},
			available("worker-c", "METADATA"),
		},
	}
	got, ok := sel.Select(snap)
	if !ok || got.WorkerID != "worker-c" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestRoundRobinRestartContinuesFromCursor(t *testing.T) {
	sel, err := New(FIFO, RoundRobin)
	if err != nil {
		t.Fatal(err)
	}
	snap := model.Snapshot{
		Operations:        []model.Operation{{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}},
		RoundRobinCursors: map[string]string{"METADATA": "worker-b"},
		Workers: []model.Worker{
			available("worker-a", "METADATA"),
			available("worker-b", "METADATA"),
			available("worker-c", "METADATA"),
		},
	}
	got, ok := sel.Select(snap)
	if !ok || got.WorkerID != "worker-c" {
		t.Fatalf("restart should continue at worker-c, got %+v ok=%t", got, ok)
	}
}

func TestRoundRobinSelectsSixAssignmentsInOrder(t *testing.T) {
	sel := mustSelector(t, FIFO, RoundRobin)
	workers := []model.Worker{
		available("worker-a", "METADATA"),
		available("worker-b", "METADATA"),
		available("worker-c", "METADATA"),
	}
	want := []string{"worker-a", "worker-b", "worker-c", "worker-a", "worker-b", "worker-c"}
	last := ""
	for i, expected := range want {
		snap := model.Snapshot{
			Operations: []model.Operation{{OperationID: "op", Type: "METADATA", CreatedAt: time.Now()}},
			Workers:    workers,
		}
		if last != "" {
			snap.RoundRobinCursors = map[string]string{"METADATA": last}
		}
		got, ok := sel.Select(snap)
		if !ok || got.WorkerID != expected || got.OperationPolicy != FIFO || got.WorkerPolicy != RoundRobin {
			t.Fatalf("step %d got %+v ok=%t want %s", i+1, got, ok, expected)
		}
		last = got.WorkerID
	}
}

func TestRoundRobinDoesNotReorderFifoOperations(t *testing.T) {
	ten := time.Date(2026, 8, 25, 10, 0, 0, 0, time.UTC)
	eleven := time.Date(2026, 8, 25, 10, 1, 0, 0, time.UTC)
	got, ok := mustSelector(t, FIFO, RoundRobin).Select(model.Snapshot{
		Operations: []model.Operation{
			{OperationID: "later", Type: "METADATA", CreatedAt: eleven},
			{OperationID: "earlier", Type: "METADATA", CreatedAt: ten},
		},
		Workers: []model.Worker{
			available("worker-b", "METADATA"),
			available("worker-a", "METADATA"),
		},
	})
	if !ok || got.OperationID != "earlier" || got.WorkerID != "worker-a" {
		t.Fatalf("RR must still pick FIFO head, got %+v ok=%t", got, ok)
	}
}

func TestRoundRobinIndependentPerOperationType(t *testing.T) {
	sel := mustSelector(t, FIFO, RoundRobin)
	thumb := model.Snapshot{
		Operations:        []model.Operation{{OperationID: "t1", Type: "THUMBNAIL", CreatedAt: time.Now()}},
		RoundRobinCursors: map[string]string{"METADATA": "worker-b", "THUMBNAIL": "worker-a"},
		Workers: []model.Worker{
			available("worker-a", "METADATA", "THUMBNAIL"),
			available("worker-b", "METADATA"),
			available("worker-c", "METADATA", "THUMBNAIL"),
		},
	}
	got, ok := sel.Select(thumb)
	if !ok || got.WorkerID != "worker-c" {
		t.Fatalf("thumbnail cursor should ignore metadata rotation, got %+v ok=%t", got, ok)
	}
}

func TestRoundRobinPlacesAudioExtractionAmongCapableWorkers(t *testing.T) {
	sel := mustSelector(t, FIFO, RoundRobin)
	snap := model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "AUDIO_EXTRACTION", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			available("worker-a", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION"),
			available("worker-b", "METADATA"),
			available("worker-c", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION"),
		},
	}
	first, ok := sel.Select(snap)
	if !ok || first.WorkerID != "worker-a" {
		t.Fatalf("got %+v ok=%t", first, ok)
	}
	snap.RoundRobinCursors = map[string]string{"AUDIO_EXTRACTION": "worker-a"}
	second, ok := sel.Select(snap)
	if !ok || second.WorkerID != "worker-c" {
		t.Fatalf("got %+v ok=%t", second, ok)
	}
}

func TestRoundRobinPlacesTranscode1080pAmongCapableWorkers(t *testing.T) {
	sel := mustSelector(t, FIFO, RoundRobin)
	snap := model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "TRANSCODE_1080P", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			available("worker-a", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P"),
			available("worker-b", "METADATA"),
			available("worker-c", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P"),
		},
	}
	first, ok := sel.Select(snap)
	if !ok || first.WorkerID != "worker-a" {
		t.Fatalf("got %+v ok=%t", first, ok)
	}
	snap.RoundRobinCursors = map[string]string{"TRANSCODE_1080P": "worker-a"}
	second, ok := sel.Select(snap)
	if !ok || second.WorkerID != "worker-c" {
		t.Fatalf("got %+v ok=%t", second, ok)
	}
}

func TestRoundRobinPlacesH264ToAV1AmongCapableWorkers(t *testing.T) {
	sel := mustSelector(t, FIFO, RoundRobin)
	snap := model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "H264_TO_AV1", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			available("worker-a", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P", "H264_TO_AV1"),
			available("worker-b", "METADATA"),
			available("worker-c", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P", "H264_TO_AV1"),
		},
	}
	first, ok := sel.Select(snap)
	if !ok || first.WorkerID != "worker-a" {
		t.Fatalf("got %+v ok=%t", first, ok)
	}
	snap.RoundRobinCursors = map[string]string{"H264_TO_AV1": "worker-a"}
	second, ok := sel.Select(snap)
	if !ok || second.WorkerID != "worker-c" {
		t.Fatalf("got %+v ok=%t", second, ok)
	}
}
