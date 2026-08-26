package worker

import (
	"context"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/lease"
)

const (
	DefaultHeartbeatInterval = 5 * time.Second
	heartbeatRequestTimeout  = 2 * time.Second
)

func ParseHeartbeatInterval(raw string) (time.Duration, error) {
	if strings.TrimSpace(raw) == "" {
		return DefaultHeartbeatInterval, nil
	}
	parsed, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("invalid HEARTBEAT_INTERVAL %q: %w", raw, err)
	}
	if parsed <= 0 {
		return 0, fmt.Errorf("invalid HEARTBEAT_INTERVAL %q: must be greater than 0", raw)
	}
	return parsed, nil
}

func ParseLeaseRenewInterval(raw string) (time.Duration, error) {
	if strings.TrimSpace(raw) == "" {
		return lease.DefaultRenewInterval, nil
	}
	parsed, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("invalid LEASE_RENEW_INTERVAL %q: %w", raw, err)
	}
	if parsed <= 0 {
		return 0, fmt.Errorf("invalid LEASE_RENEW_INTERVAL %q: must be greater than 0", raw)
	}
	return parsed, nil
}

func (w *Worker) runHeartbeatLoop(ctx context.Context) {
	w.pulse(ctx)
	ticker := time.NewTicker(w.cfg.HeartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.pulse(ctx)
		}
	}
}

func (w *Worker) pulse(ctx context.Context) {
	if ctx.Err() != nil {
		return
	}
	hbCtx, cancel := context.WithTimeout(ctx, heartbeatRequestTimeout)
	defer cancel()
	if err := w.heartbeat(hbCtx); err != nil {
		log.Printf("worker_id=%s event=heartbeat_error err=%v", w.cfg.WorkerID, err)
	}
}
