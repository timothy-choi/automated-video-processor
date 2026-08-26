package lease

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func TestRunLoopStopsWhenContextCanceled(t *testing.T) {
	var calls atomic.Int32
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		RunLoop(ctx, 15*time.Millisecond, func(context.Context) error {
			calls.Add(1)
			return nil
		})
	}()
	time.Sleep(50 * time.Millisecond)
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("renew loop did not stop")
	}
	if calls.Load() < 1 {
		t.Fatalf("calls=%d", calls.Load())
	}
	after := calls.Load()
	time.Sleep(40 * time.Millisecond)
	if calls.Load() != after {
		t.Fatalf("loop continued after cancel: before=%d after=%d", after, calls.Load())
	}
}

func TestRunLoopContinuesAfterTransientFailure(t *testing.T) {
	var calls atomic.Int32
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go RunLoop(ctx, 15*time.Millisecond, func(context.Context) error {
		n := calls.Add(1)
		if n == 1 {
			return errors.New("temporary")
		}
		return nil
	})
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if calls.Load() >= 2 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("calls=%d", calls.Load())
}
