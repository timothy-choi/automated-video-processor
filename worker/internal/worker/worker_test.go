package worker

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/timothy-choi/automated-video-processor/worker/internal/capability"
	"github.com/timothy-choi/automated-video-processor/worker/internal/client"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
)

func TestNewDefaultsPrefetch(t *testing.T) {
	w := New(Config{WorkerID: "worker-a", ControlServiceURL: "http://localhost:8080"}, storage.NewMemoryStore())
	if w.cfg.WorkerID != "worker-a" {
		t.Fatalf("workerID=%s", w.cfg.WorkerID)
	}
	if w.cfg.Prefetch != 1 {
		t.Fatalf("prefetch=%d", w.cfg.Prefetch)
	}
	if w.cfg.HeartbeatInterval != DefaultHeartbeatInterval {
		t.Fatalf("heartbeat interval=%s", w.cfg.HeartbeatInterval)
	}
}

func TestNewKeepsExplicitWorkerID(t *testing.T) {
	w := New(Config{WorkerID: "worker-b", Prefetch: 1, ControlServiceURL: "http://localhost:8080"}, storage.NewMemoryStore())
	if w.cfg.WorkerID != "worker-b" {
		t.Fatalf("workerID=%s", w.cfg.WorkerID)
	}
}

func TestRunRequiresWorkerID(t *testing.T) {
	heartbeats := 0
	w := New(Config{ControlServiceURL: "http://localhost:8080"}, storage.NewMemoryStore())
	w.heartbeat = func(ctx context.Context) error {
		heartbeats++
		return nil
	}
	if err := w.Run(context.Background()); err == nil || !strings.Contains(err.Error(), "WORKER_ID") {
		t.Fatalf("err=%v", err)
	}
	if heartbeats != 0 {
		t.Fatalf("heartbeats=%d", heartbeats)
	}
}

func TestRunRegistersBeforeConsume(t *testing.T) {
	var order []string
	w := New(Config{WorkerID: "worker-a", ControlServiceURL: "http://localhost:8080"}, storage.NewMemoryStore())
	w.detect = func(ctx context.Context) (capability.Snapshot, error) {
		order = append(order, "detect")
		return testSnapshot(), nil
	}
	w.register = func(ctx context.Context, req model.RegisterWorkerRequest) (model.RegisterWorkerResponse, error) {
		order = append(order, "register")
		if req.WorkerID != "worker-a" {
			t.Fatalf("workerId=%s", req.WorkerID)
		}
		if strings.Join(req.SupportedOperations, ",") != "METADATA,THUMBNAIL" {
			t.Fatalf("operations=%v", req.SupportedOperations)
		}
		return model.RegisterWorkerResponse{WorkerID: "worker-a", Status: "AVAILABLE"}, nil
	}
	w.heartbeat = func(ctx context.Context) error { return nil }
	w.consume = func(ctx context.Context, supported []string) error {
		order = append(order, "consume")
		if strings.Join(supported, ",") != "METADATA,THUMBNAIL" {
			t.Fatalf("supported=%v", supported)
		}
		return nil
	}
	if err := w.Run(context.Background()); err != nil {
		t.Fatal(err)
	}
	if strings.Join(order, ",") != "detect,register,consume" {
		t.Fatalf("order=%v", order)
	}
}

func TestRunDoesNotRegisterOrConsumeWhenDetectFails(t *testing.T) {
	registered := false
	consumed := false
	w := New(Config{WorkerID: "worker-a", ControlServiceURL: "http://localhost:8080"}, storage.NewMemoryStore())
	w.detect = func(ctx context.Context) (capability.Snapshot, error) {
		return capability.Snapshot{}, errors.New("METADATA requires ffprobe")
	}
	w.register = func(ctx context.Context, req model.RegisterWorkerRequest) (model.RegisterWorkerResponse, error) {
		registered = true
		return model.RegisterWorkerResponse{}, nil
	}
	w.heartbeat = func(ctx context.Context) error {
		t.Fatal("heartbeat must not start when detect fails")
		return nil
	}
	w.consume = func(ctx context.Context, supported []string) error {
		consumed = true
		return nil
	}
	if err := w.Run(context.Background()); err == nil {
		t.Fatal("expected detect failure")
	}
	if registered || consumed {
		t.Fatalf("registered=%t consumed=%t", registered, consumed)
	}
}

func TestRunDoesNotConsumeWhenRegistrationFails(t *testing.T) {
	consumed := false
	w := New(Config{WorkerID: "worker-a", ControlServiceURL: "http://localhost:8080"}, storage.NewMemoryStore())
	w.detect = func(ctx context.Context) (capability.Snapshot, error) {
		return testSnapshot(), nil
	}
	w.register = func(ctx context.Context, req model.RegisterWorkerRequest) (model.RegisterWorkerResponse, error) {
		return model.RegisterWorkerResponse{}, &client.StatusError{Status: 400, Body: "INVALID_REGISTRATION"}
	}
	w.heartbeat = func(ctx context.Context) error {
		t.Fatal("heartbeat must not start after registration failure")
		return nil
	}
	w.consume = func(ctx context.Context, supported []string) error {
		consumed = true
		return nil
	}
	if err := w.Run(context.Background()); err == nil {
		t.Fatal("expected registration failure")
	}
	if consumed {
		t.Fatal("consume must not start after registration failure")
	}
}

func TestRunRetriesRetryableRegistrationThenConsumes(t *testing.T) {
	attempts := 0
	consumed := false
	w := New(Config{WorkerID: "worker-a", ControlServiceURL: "http://localhost:8080"}, storage.NewMemoryStore())
	w.detect = func(ctx context.Context) (capability.Snapshot, error) {
		return testSnapshot(), nil
	}
	w.register = func(ctx context.Context, req model.RegisterWorkerRequest) (model.RegisterWorkerResponse, error) {
		attempts++
		if attempts < 2 {
			return model.RegisterWorkerResponse{}, errors.New("connection refused")
		}
		return model.RegisterWorkerResponse{WorkerID: "worker-a", Status: "AVAILABLE"}, nil
	}
	w.heartbeat = func(ctx context.Context) error { return nil }
	w.consume = func(ctx context.Context, supported []string) error {
		consumed = true
		return nil
	}
	if err := w.Run(context.Background()); err != nil {
		t.Fatal(err)
	}
	if attempts != 2 || !consumed {
		t.Fatalf("attempts=%d consumed=%t", attempts, consumed)
	}
}

func testSnapshot() capability.Snapshot {
	return capability.Snapshot{
		Hostname:            "mac-worker-a",
		CPUArchitecture:     "arm64",
		CPUCores:            8,
		MemoryBytes:         17179869184,
		FFmpegVersion:       "7.1",
		SupportedCodecs:     []string{"h264"},
		SupportedOperations: []string{"METADATA", "THUMBNAIL"},
	}
}
