package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/client"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/policy"
	"github.com/timothy-choi/automated-video-processor/scheduler/internal/scheduler"
)

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}
	selected, err := policy.New(cfg.policy)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf(
		"event=scheduler_start policy=%s control=%s poll_interval=%s",
		selected.Name(),
		cfg.controlURL,
		cfg.pollInterval,
	)
	loop := &scheduler.Loop{
		Client:       client.New(cfg.controlURL, 15*time.Second),
		Policy:       selected,
		PollInterval: cfg.pollInterval,
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err := loop.Run(ctx); err != nil && err != context.Canceled {
		log.Fatal(err)
	}
}

type config struct {
	controlURL   string
	pollInterval time.Duration
	policy       string
}

func loadConfig() (config, error) {
	interval, err := parseDuration(envOr("SCHEDULER_POLL_INTERVAL", "500ms"))
	if err != nil {
		return config{}, err
	}
	return config{
		controlURL:   envOr("CONTROL_SERVICE_URL", "http://localhost:8080"),
		pollInterval: interval,
		policy:       envOr("SCHEDULING_POLICY", "FIFO"),
	}, nil
}

func parseDuration(raw string) (time.Duration, error) {
	parsed, err := time.ParseDuration(raw)
	if err != nil {
		return 0, err
	}
	if parsed <= 0 {
		return 0, errInvalidInterval(raw)
	}
	return parsed, nil
}

type invalidIntervalError string

func (e invalidIntervalError) Error() string {
	return "invalid SCHEDULER_POLL_INTERVAL: " + string(e)
}

func errInvalidInterval(raw string) error {
	return invalidIntervalError(raw)
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
