package worker

import (
	"testing"

	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
)

func TestNewDefaultsWorkerIDAndPrefetch(t *testing.T) {
	w := New(Config{ControlServiceURL: "http://localhost:8080"}, storage.NewMemoryStore())
	if w.cfg.WorkerID != "worker" {
		t.Fatalf("workerID=%s", w.cfg.WorkerID)
	}
	if w.cfg.Prefetch != 1 {
		t.Fatalf("prefetch=%d", w.cfg.Prefetch)
	}
}

func TestNewKeepsExplicitWorkerID(t *testing.T) {
	w := New(Config{WorkerID: "worker-b", Prefetch: 1, ControlServiceURL: "http://localhost:8080"}, storage.NewMemoryStore())
	if w.cfg.WorkerID != "worker-b" {
		t.Fatalf("workerID=%s", w.cfg.WorkerID)
	}
}
