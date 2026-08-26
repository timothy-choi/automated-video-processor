package policy

import (
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
)

func TestLeastLoadedChoosesIdleWorker(t *testing.T) {
	sel := mustSelector(t, FIFO, LeastLoaded)
	got, ok := sel.Select(model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			loaded("worker-a", 2, "METADATA"),
			loaded("worker-b", 0, "METADATA"),
			loaded("worker-c", 1, "METADATA"),
		},
	})
	if !ok || got.WorkerID != "worker-b" || got.ActiveOperations != 0 || got.WorkerPolicy != LeastLoaded {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestLeastLoadedTieBreaksByWorkerID(t *testing.T) {
	got, ok := mustSelector(t, FIFO, LeastLoaded).Select(model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			loaded("worker-b", 1, "METADATA"),
			loaded("worker-a", 1, "METADATA"),
			loaded("worker-c", 2, "METADATA"),
		},
	})
	if !ok || got.WorkerID != "worker-a" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestLeastLoadedIgnoresIncapableIdleWorker(t *testing.T) {
	got, ok := mustSelector(t, FIFO, LeastLoaded).Select(model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "THUMBNAIL", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			loaded("worker-a", 2, "METADATA", "THUMBNAIL"),
			loaded("worker-b", 0, "METADATA"),
			loaded("worker-c", 1, "METADATA", "THUMBNAIL"),
		},
	})
	if !ok || got.WorkerID != "worker-c" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestLeastLoadedPlacesAudioExtractionOnIdleCapableWorker(t *testing.T) {
	got, ok := mustSelector(t, FIFO, LeastLoaded).Select(model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "AUDIO_EXTRACTION", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			loaded("worker-a", 0, "METADATA", "THUMBNAIL"),
			loaded("worker-b", 1, "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION"),
			loaded("worker-c", 0, "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION"),
		},
	})
	if !ok || got.WorkerID != "worker-c" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestLeastLoadedPlacesTranscode1080pOnIdleCapableWorker(t *testing.T) {
	got, ok := mustSelector(t, FIFO, LeastLoaded).Select(model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "TRANSCODE_1080P", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			loaded("worker-a", 0, "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION"),
			loaded("worker-b", 1, "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P"),
			loaded("worker-c", 0, "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P"),
		},
	})
	if !ok || got.WorkerID != "worker-c" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestLeastLoadedIgnoresUnavailableIdleWorker(t *testing.T) {
	got, ok := mustSelector(t, FIFO, LeastLoaded).Select(model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "METADATA", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			{ID: "worker-a", Status: "UNAVAILABLE", SupportedOperations: []string{"METADATA"}, ActiveOperations: 0},
			loaded("worker-b", 2, "METADATA"),
		},
	})
	if !ok || got.WorkerID != "worker-b" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func TestLeastLoadedNoEligibleWorkerReturnsNoPlacement(t *testing.T) {
	got, ok := mustSelector(t, FIFO, LeastLoaded).Select(model.Snapshot{
		Operations: []model.Operation{{OperationID: "op-1", Type: "THUMBNAIL", CreatedAt: time.Now()}},
		Workers: []model.Worker{
			loaded("worker-a", 0, "METADATA"),
			{ID: "worker-b", Status: "UNAVAILABLE", SupportedOperations: []string{"THUMBNAIL"}, ActiveOperations: 0},
		},
	})
	if ok {
		t.Fatalf("unexpected placement %+v", got)
	}
}

func TestLeastLoadedDoesNotReorderFifoOperations(t *testing.T) {
	ten := time.Date(2026, 8, 26, 10, 0, 0, 0, time.UTC)
	eleven := time.Date(2026, 8, 26, 10, 1, 0, 0, time.UTC)
	got, ok := mustSelector(t, FIFO, LeastLoaded).Select(model.Snapshot{
		Operations: []model.Operation{
			{OperationID: "later", Type: "METADATA", CreatedAt: eleven},
			{OperationID: "earlier", Type: "METADATA", CreatedAt: ten},
		},
		Workers: []model.Worker{
			loaded("worker-b", 0, "METADATA"),
			loaded("worker-a", 1, "METADATA"),
		},
	})
	if !ok || got.OperationID != "earlier" || got.WorkerID != "worker-b" {
		t.Fatalf("got %+v ok=%t", got, ok)
	}
}

func loaded(id string, active int, ops ...string) model.Worker {
	w := available(id, ops...)
	w.ActiveOperations = active
	return w
}
