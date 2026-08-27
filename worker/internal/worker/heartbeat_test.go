package worker

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/capability"
	"github.com/timothy-choi/automated-video-processor/worker/internal/lease"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/storage"
)

func TestParseHeartbeatInterval(t *testing.T) {
	got, err := ParseHeartbeatInterval("")
	if err != nil || got != DefaultHeartbeatInterval {
		t.Fatalf("empty: got=%s err=%v", got, err)
	}
	got, err = ParseHeartbeatInterval("5s")
	if err != nil || got != 5*time.Second {
		t.Fatalf("5s: got=%s err=%v", got, err)
	}
	got, err = ParseHeartbeatInterval("250ms")
	if err != nil || got != 250*time.Millisecond {
		t.Fatalf("250ms: got=%s err=%v", got, err)
	}
	if _, err = ParseHeartbeatInterval("0s"); err == nil {
		t.Fatal("expected error for 0s")
	}
	if _, err = ParseHeartbeatInterval("-1s"); err == nil {
		t.Fatal("expected error for -1s")
	}
	if _, err = ParseHeartbeatInterval("abc"); err == nil {
		t.Fatal("expected error for abc")
	}
	if _, err = ParseHeartbeatInterval("5"); err == nil {
		t.Fatal("expected error for unitless 5")
	}
}

func TestParseLeaseRenewInterval(t *testing.T) {
	got, err := ParseLeaseRenewInterval("")
	if err != nil || got != lease.DefaultRenewInterval {
		t.Fatalf("empty: got=%s err=%v", got, err)
	}
	got, err = ParseLeaseRenewInterval("10s")
	if err != nil || got != 10*time.Second {
		t.Fatalf("10s: got=%s err=%v", got, err)
	}
	if _, err = ParseLeaseRenewInterval("0s"); err == nil {
		t.Fatal("expected error for 0s")
	}
}

func TestParseExecutionTimeout(t *testing.T) {
	got, err := ParseExecutionTimeout("")
	if err != nil || got != 0 {
		t.Fatalf("empty: got=%s err=%v", got, err)
	}
	got, err = ParseExecutionTimeout("0")
	if err != nil || got != 0 {
		t.Fatalf("0: got=%s err=%v", got, err)
	}
	got, err = ParseExecutionTimeout("0s")
	if err != nil || got != 0 {
		t.Fatalf("0s: got=%s err=%v", got, err)
	}
	got, err = ParseExecutionTimeout("15m")
	if err != nil || got != 15*time.Minute {
		t.Fatalf("15m: got=%s err=%v", got, err)
	}
	if _, err = ParseExecutionTimeout("-1s"); err == nil {
		t.Fatal("expected error for -1s")
	}
	if _, err = ParseExecutionTimeout("abc"); err == nil {
		t.Fatal("expected error for abc")
	}
}

func TestHeartbeatLoopStartsAfterRegistrationAndWhileIdle(t *testing.T) {
	var heartbeats atomic.Int64
	started := make(chan struct{})
	w := newHeartbeatTestWorker(t, 15*time.Millisecond)
	w.heartbeat = func(ctx context.Context) error {
		if heartbeats.Add(1) == 1 {
			close(started)
		}
		return nil
	}
	w.consume = func(ctx context.Context, supported []string) error {
		select {
		case <-started:
		case <-time.After(time.Second):
			t.Fatal("heartbeat did not start after registration")
		}
		deadline := time.After(time.Second)
		for heartbeats.Load() < 3 {
			select {
			case <-deadline:
				t.Fatalf("idle heartbeats=%d", heartbeats.Load())
			case <-time.After(10 * time.Millisecond):
			}
		}
		return nil
	}
	if err := w.Run(context.Background()); err != nil {
		t.Fatal(err)
	}
	if heartbeats.Load() < 3 {
		t.Fatalf("heartbeats=%d", heartbeats.Load())
	}
}

func TestHeartbeatLoopContinuesDuringBlockingWork(t *testing.T) {
	var heartbeats atomic.Int64
	w := newHeartbeatTestWorker(t, 15*time.Millisecond)
	w.heartbeat = func(ctx context.Context) error {
		heartbeats.Add(1)
		return nil
	}
	w.consume = func(ctx context.Context, supported []string) error {
		// Simulate a long media operation that blocks the consumer.
		time.Sleep(80 * time.Millisecond)
		if heartbeats.Load() < 2 {
			t.Fatalf("heartbeats during work=%d", heartbeats.Load())
		}
		return nil
	}
	if err := w.Run(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestHeartbeatFailureDoesNotKillWorker(t *testing.T) {
	var calls atomic.Int64
	w := newHeartbeatTestWorker(t, 15*time.Millisecond)
	w.heartbeat = func(ctx context.Context) error {
		n := calls.Add(1)
		if n == 1 {
			return errors.New("connection refused")
		}
		return nil
	}
	w.consume = func(ctx context.Context, supported []string) error {
		deadline := time.After(time.Second)
		for calls.Load() < 2 {
			select {
			case <-deadline:
				t.Fatalf("calls=%d", calls.Load())
			case <-time.After(10 * time.Millisecond):
			}
		}
		return nil
	}
	if err := w.Run(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestHeartbeatLoopStopsOnCancel(t *testing.T) {
	var heartbeats atomic.Int64
	w := newHeartbeatTestWorker(t, 15*time.Millisecond)
	w.heartbeat = func(ctx context.Context) error {
		heartbeats.Add(1)
		return nil
	}
	w.consume = func(ctx context.Context, supported []string) error {
		<-ctx.Done()
		return ctx.Err()
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- w.Run(ctx) }()

	deadline := time.After(time.Second)
	for heartbeats.Load() < 2 {
		select {
		case <-deadline:
			t.Fatalf("heartbeats=%d", heartbeats.Load())
		case <-time.After(10 * time.Millisecond):
		}
	}
	cancel()
	select {
	case err := <-done:
		if err != nil && err != context.Canceled {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("run did not stop")
	}
	n := heartbeats.Load()
	time.Sleep(50 * time.Millisecond)
	if heartbeats.Load() != n {
		t.Fatalf("heartbeat continued after cancel before=%d after=%d", n, heartbeats.Load())
	}
}

func TestRunStillConsumesAfterHeartbeatStartup(t *testing.T) {
	consumed := false
	var heartbeats atomic.Int64
	w := newHeartbeatTestWorker(t, time.Hour)
	w.heartbeat = func(ctx context.Context) error {
		heartbeats.Add(1)
		return nil
	}
	w.consume = func(ctx context.Context, supported []string) error {
		deadline := time.After(time.Second)
		for heartbeats.Load() < 1 {
			select {
			case <-deadline:
				t.Fatal("expected heartbeat before consume returned")
			case <-time.After(5 * time.Millisecond):
			}
		}
		consumed = true
		return nil
	}
	if err := w.Run(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !consumed {
		t.Fatal("expected consume after registration/heartbeat startup")
	}
}

func newHeartbeatTestWorker(t *testing.T, interval time.Duration) *Worker {
	t.Helper()
	w := New(Config{
		WorkerID:          "worker-a",
		ControlServiceURL: "http://localhost:8080",
		HeartbeatInterval: interval,
	}, storage.NewMemoryStore())
	w.detect = func(ctx context.Context) (capability.Snapshot, error) {
		return testSnapshot(), nil
	}
	w.register = func(ctx context.Context, req model.RegisterWorkerRequest) (model.RegisterWorkerResponse, error) {
		return model.RegisterWorkerResponse{WorkerID: "worker-a", Status: "AVAILABLE"}, nil
	}
	return w
}
