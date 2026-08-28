package client

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/scheduler/internal/model"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
)

func TestSnapshotAndAssign(t *testing.T) {
	var assigned model.AssignRequest
	var snapshotAuth, assignAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/internal/scheduler/snapshot":
			snapshotAuth = r.Header.Get("Authorization")
			_ = json.NewEncoder(w).Encode(model.Snapshot{
				Operations: []model.Operation{{
					OperationID: "op-1",
					JobID:       "job-1",
					Type:        "METADATA",
					CreatedAt:   time.Date(2026, 8, 25, 10, 0, 0, 0, time.UTC),
				}},
				Workers: []model.Worker{{
					ID:                  "worker-a",
					Status:              "AVAILABLE",
					SupportedOperations: []string{"METADATA"},
				}},
				RoundRobinCursors: map[string]string{"METADATA": "worker-b"},
			})
		case r.Method == http.MethodPost && r.URL.Path == "/internal/scheduler/assign":
			assignAuth = r.Header.Get("Authorization")
			if err := json.NewDecoder(r.Body).Decode(&assigned); err != nil {
				t.Fatal(err)
			}
			_ = json.NewEncoder(w).Encode(model.AssignResponse{
				DecisionID:      "dec-1",
				OperationID:     assigned.OperationID,
				WorkerID:        assigned.WorkerID,
				Policy:          assigned.OperationPolicy,
				OperationPolicy: assigned.OperationPolicy,
				WorkerPolicy:    assigned.WorkerPolicy,
				RoutingKey:      "worker.worker-a",
			})
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	c := New(server.URL, time.Second, "test-scheduler-token")
	snap, err := c.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(snap.Operations) != 1 || snap.Operations[0].OperationID != "op-1" {
		t.Fatalf("snapshot %+v", snap)
	}
	if snap.RoundRobinCursors["METADATA"] != "worker-b" {
		t.Fatalf("cursors %+v", snap.RoundRobinCursors)
	}
	resp, err := c.Assign(context.Background(), model.Placement{
		OperationID:     "op-1",
		WorkerID:        "worker-a",
		OperationPolicy: "FIFO",
		WorkerPolicy:    "ROUND_ROBIN",
	})
	if err != nil {
		t.Fatal(err)
	}
	if assigned.WorkerID != "worker-a" || assigned.WorkerPolicy != "ROUND_ROBIN" || resp.DecisionID != "dec-1" {
		t.Fatalf("assigned=%+v resp=%+v", assigned, resp)
	}
	if snapshotAuth != "Bearer test-scheduler-token" || assignAuth != "Bearer test-scheduler-token" {
		t.Fatalf("snapshotAuth=%q assignAuth=%q", snapshotAuth, assignAuth)
	}
}

func TestAssignPropagatesIncomingTraceContext(t *testing.T) {
	var got string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/internal/scheduler/assign" {
			got = r.Header.Get("traceparent")
			_ = json.NewEncoder(w).Encode(model.AssignResponse{DecisionID: "dec-1", OperationID: "op-1", WorkerID: "worker-a"})
			return
		}
		http.NotFound(w, r)
	}))
	defer server.Close()

	exp := tracetest.NewInMemoryExporter()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exp), sdktrace.WithSampler(sdktrace.AlwaysSample()))
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.TraceContext{})
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })

	ctx, span := otel.Tracer("test").Start(context.Background(), "scheduler.assign")
	defer span.End()
	c := New(server.URL, time.Second, "test-scheduler-token")
	if _, err := c.Assign(ctx, model.Placement{OperationID: "op-1", WorkerID: "worker-a", OperationPolicy: "FIFO", WorkerPolicy: "FIFO"}); err != nil {
		t.Fatal(err)
	}
	if got == "" || !strings.Contains(got, span.SpanContext().TraceID().String()) {
		t.Fatalf("traceparent=%q", got)
	}
}

func TestAssignConflict(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"code":"INVALID_OPERATION_STATE"}`))
	}))
	defer server.Close()
	c := New(server.URL, time.Second, "test-scheduler-token")
	_, err := c.Assign(context.Background(), model.Placement{OperationID: "op-1", WorkerID: "worker-a", OperationPolicy: "FIFO", WorkerPolicy: "LEXICOGRAPHIC"})
	if !IsConflict(err) {
		t.Fatalf("err=%v", err)
	}
}

func TestUnauthorizedSnapshot(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"code":"UNAUTHORIZED"}`))
	}))
	defer server.Close()
	_, err := New(server.URL, time.Second, "wrong-token").Snapshot(context.Background())
	if !IsUnauthorized(err) {
		t.Fatalf("err=%v", err)
	}
}

func TestForbiddenAssign(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"code":"FORBIDDEN"}`))
	}))
	defer server.Close()
	_, err := New(server.URL, time.Second, "worker-token").Assign(context.Background(), model.Placement{
		OperationID:     "op-1",
		WorkerID:        "worker-a",
		OperationPolicy: "FIFO",
		WorkerPolicy:    "LEXICOGRAPHIC",
	})
	if !IsUnauthorized(err) {
		t.Fatalf("err=%v", err)
	}
}

func TestErrorsDoNotIncludeSchedulerToken(t *testing.T) {
	secret := "super-secret-scheduler-token-do-not-log"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"code":"UNAUTHORIZED"}`))
	}))
	defer server.Close()
	_, err := New(server.URL, time.Second, secret).Snapshot(context.Background())
	if err == nil {
		t.Fatal("expected error")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("token leaked in error: %v", err)
	}
}
