package broker

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/testcontainers/testcontainers-go/modules/rabbitmq"

	"github.com/timothy-choi/automated-video-processor/worker/internal/consumer"
	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
	"github.com/timothy-choi/automated-video-processor/worker/internal/run"
)

func TestConsumerAcksAfterCompleteAgainstRealBroker(t *testing.T) {
	url := startRabbit(t)
	ctrl := &recordingControl{outcomes: map[string]string{
		"11111111-1111-1111-1111-111111111111": model.StartStarted,
	}}
	executed := make(chan string, 1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		_ = New(Config{URL: url, WorkerID: "worker-a", Prefetch: 1}, ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
			executed <- claimed.OperationID
			format := "mp4"
			return run.Result{RuntimeMs: 7, Metadata: &model.MetadataResult{FormatName: &format}}, nil
		}).Run(ctx)
	}()

	publishAssignment(t, url, "11111111-1111-1111-1111-111111111111", "METADATA")
	select {
	case op := <-executed:
		if op != "11111111-1111-1111-1111-111111111111" {
			t.Fatalf("operation=%s", op)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("timed out waiting for consumer")
	}
	waitUntil(t, 5*time.Second, func() bool { return ctrl.completeCount() == 1 })
}

func TestTwoWorkersProcessDistinctMessages(t *testing.T) {
	url := startRabbit(t)
	ops := []string{
		"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
		"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
		"cccccccc-cccc-cccc-cccc-cccccccccccc",
		"dddddddd-dddd-dddd-dddd-dddddddddddd",
	}
	outcomes := map[string]string{}
	for _, op := range ops {
		outcomes[op] = model.StartStarted
	}
	ctrl := &recordingControl{outcomes: outcomes}
	var mu sync.Mutex
	seen := map[string]string{}
	execFor := func(workerID string) consumer.Executor {
		return func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
			time.Sleep(150 * time.Millisecond)
			mu.Lock()
			seen[claimed.OperationID] = workerID
			mu.Unlock()
			return run.Result{RuntimeMs: 4}, nil
		}
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = New(Config{URL: url, WorkerID: "worker-a", Prefetch: 1}, ctrl, execFor("worker-a")).Run(ctx) }()
	go func() { _ = New(Config{URL: url, WorkerID: "worker-b", Prefetch: 1}, ctrl, execFor("worker-b")).Run(ctx) }()

	for _, op := range ops {
		publishAssignment(t, url, op, "THUMBNAIL")
	}

	waitUntil(t, 30*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return len(seen) == len(ops)
	})
	workers := map[string]int{}
	mu.Lock()
	for op, workerID := range seen {
		workers[workerID]++
		t.Logf("operation %s processed by %s", op, workerID)
	}
	mu.Unlock()
	if len(seen) != len(ops) {
		t.Fatalf("processed=%d", len(seen))
	}
	if len(workers) < 2 {
		t.Fatalf("expected both workers to process work, distribution=%v", workers)
	}
}

func TestDuplicateDeliveryDoesNotExecuteTwice(t *testing.T) {
	url := startRabbit(t)
	ctrl := &recordingControl{outcomes: map[string]string{
		"eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee": model.StartStarted,
	}}
	var mu sync.Mutex
	execCount := 0
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		_ = New(Config{URL: url, WorkerID: "worker-a", Prefetch: 1}, ctrl, func(ctx context.Context, claimed *model.ClaimedOperation) (run.Result, error) {
			mu.Lock()
			execCount++
			mu.Unlock()
			ctrl.setOutcome(claimed.OperationID, model.StartAlreadyTerminal)
			return run.Result{RuntimeMs: 1}, nil
		}).Run(ctx)
	}()

	body := assignmentJSON("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", "METADATA")
	publishRaw(t, url, body)
	waitUntil(t, 20*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return execCount == 1 && ctrl.completeCount() == 1
	})
	publishRaw(t, url, body)
	time.Sleep(2 * time.Second)
	mu.Lock()
	defer mu.Unlock()
	if execCount != 1 {
		t.Fatalf("duplicate delivery executed media work %d times", execCount)
	}
}

func startRabbit(t *testing.T) string {
	t.Helper()
	ctx := context.Background()
	container, err := rabbitmq.Run(ctx, "rabbitmq:3.13-management-alpine")
	if err != nil {
		t.Fatalf("rabbitmq container unavailable: %v", err)
	}
	t.Cleanup(func() {
		_ = container.Terminate(context.Background())
	})
	url, err := container.AmqpURL(ctx)
	if err != nil {
		t.Fatal(err)
	}
	return url
}

func publishAssignment(t *testing.T, url, operationID, opType string) {
	t.Helper()
	publishRaw(t, url, assignmentJSON(operationID, opType))
}

func assignmentJSON(operationID, opType string) string {
	return fmt.Sprintf(`{"schemaVersion":1,"operationId":"%s","jobId":"22222222-2222-2222-2222-222222222222","type":"%s","inputUri":"s3://media-input/sample.mp4","dispatchedAt":"2026-08-25T02:00:00Z"}`, operationID, opType)
}

func publishRaw(t *testing.T, url, body string) {
	t.Helper()
	conn, err := amqp.Dial(url)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	ch, err := conn.Channel()
	if err != nil {
		t.Fatal(err)
	}
	defer ch.Close()
	if err := DeclareTopology(ch); err != nil {
		t.Fatal(err)
	}
	err = ch.Publish(Exchange, RoutingKey, false, false, amqp.Publishing{
		ContentType:  "application/json",
		DeliveryMode: amqp.Persistent,
		Body:         []byte(body),
	})
	if err != nil {
		t.Fatal(err)
	}
}

func waitUntil(t *testing.T, timeout time.Duration, fn func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if fn() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("timed out")
}

type recordingControl struct {
	mu        sync.Mutex
	outcomes  map[string]string
	completes int
}

func (r *recordingControl) setOutcome(operationID, outcome string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.outcomes[operationID] = outcome
}

func (r *recordingControl) completeCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.completes
}

func (r *recordingControl) Start(ctx context.Context, operationID string) (model.StartResponse, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	outcome := r.outcomes[operationID]
	if outcome == "" {
		outcome = model.StartInvalidState
	}
	return model.StartResponse{Outcome: outcome, OperationID: operationID, Status: "RUNNING"}, nil
}

func (r *recordingControl) Complete(ctx context.Context, operationID string, request model.CompleteRequest) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.completes++
	r.outcomes[operationID] = model.StartAlreadyTerminal
	return nil
}

func (r *recordingControl) Fail(ctx context.Context, operationID string, runtimeMs *int64, reason string) error {
	return nil
}
