package client

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
)

func TestClaimParsesSuccess(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/internal/operations/claim" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{
			"operationId": "11111111-1111-1111-1111-111111111111",
			"jobId": "22222222-2222-2222-2222-222222222222",
			"type": "METADATA",
			"inputUri": "file:///tmp/sample.mp4",
			"claimedAt": "2026-08-24T18:00:00Z",
			"status": "RUNNING"
		}`)
	}))
	defer server.Close()

	claimed, ok, err := New(server.URL, 5*time.Second).Claim(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !ok {
		t.Fatal("expected claimed operation")
	}
	if claimed.OperationID != "11111111-1111-1111-1111-111111111111" {
		t.Fatalf("operationId = %s", claimed.OperationID)
	}
	if claimed.InputURI != "file:///tmp/sample.mp4" {
		t.Fatalf("inputUri = %s", claimed.InputURI)
	}
}

func TestClaimNoContent(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	claimed, ok, err := New(server.URL, 5*time.Second).Claim(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if ok || claimed != nil {
		t.Fatal("expected no work")
	}
}

func TestCompleteSendsPayload(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/operations/op-1/complete" {
			t.Fatalf("path = %s", r.URL.Path)
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Fatalf("content-type = %s", r.Header.Get("Content-Type"))
		}
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if body["actualRuntimeMs"].(float64) != 12 {
			t.Fatalf("runtime = %v", body["actualRuntimeMs"])
		}
		metadata, ok := body["metadata"].(map[string]any)
		if !ok || metadata["formatName"] != "mp4" {
			t.Fatalf("metadata = %v", body["metadata"])
		}
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"status":"COMPLETED"}`)
	}))
	defer server.Close()

	format := "mp4"
	err := New(server.URL, 5*time.Second).Complete(context.Background(), "op-1", model.CompleteRequest{
		ActualRuntimeMs: 12,
		Metadata: &model.MetadataResult{
			FormatName: &format,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestStartParsesOutcome(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/internal/operations/op-9/start" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{
			"outcome": "STARTED",
			"operationId": "op-9",
			"jobId": "job-9",
			"type": "METADATA",
			"inputUri": "s3://media-input/sample.mp4",
			"status": "RUNNING"
		}`)
	}))
	defer server.Close()

	started, err := New(server.URL, 5*time.Second).Start(context.Background(), "op-9")
	if err != nil {
		t.Fatal(err)
	}
	if started.Outcome != model.StartStarted {
		t.Fatalf("outcome=%s", started.Outcome)
	}
}

func TestStartNotFoundIsStatusError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = io.WriteString(w, `{"code":"OPERATION_NOT_FOUND"}`)
	}))
	defer server.Close()

	_, err := New(server.URL, 5*time.Second).Start(context.Background(), "missing")
	if err == nil {
		t.Fatal("expected error")
	}
	if !IsUnavailable(err) {
		var statusErr *StatusError
		if !errors.As(err, &statusErr) || statusErr.Status != http.StatusNotFound {
			t.Fatalf("err=%v", err)
		}
	} else {
		t.Fatalf("404 should not be unavailable: %v", err)
	}
}

func TestRegisterWorkerSuccess(t *testing.T) {
	var gotBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/internal/workers/register" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Fatalf("content-type=%s", r.Header.Get("Content-Type"))
		}
		if err := json.NewDecoder(r.Body).Decode(&gotBody); err != nil {
			t.Fatal(err)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{
			"workerId": "worker-a",
			"status": "AVAILABLE",
			"lastHeartbeat": "2026-08-25T20:00:00Z",
			"registeredAt": "2026-08-25T20:00:00Z",
			"updatedAt": "2026-08-25T20:00:00Z"
		}`)
	}))
	defer server.Close()

	resp, err := New(server.URL, 5*time.Second).RegisterWorker(context.Background(), model.RegisterWorkerRequest{
		WorkerID:            "worker-a",
		Hostname:            "mac-worker-a",
		SupportedOperations: []string{"METADATA", "THUMBNAIL"},
		SupportedCodecs:     []string{"h264"},
		CPUArchitecture:     "arm64",
		CPUCores:            8,
		MemoryBytes:         17179869184,
		FFmpegVersion:       "7.1",
	})
	if err != nil {
		t.Fatal(err)
	}
	if resp.WorkerID != "worker-a" || resp.Status != "AVAILABLE" {
		t.Fatalf("resp=%+v", resp)
	}
	if gotBody["workerId"] != "worker-a" {
		t.Fatalf("body=%v", gotBody)
	}
	ops, _ := gotBody["supportedOperations"].([]any)
	if len(ops) != 2 {
		t.Fatalf("operations=%v", gotBody["supportedOperations"])
	}
}

func TestRegisterWorkerUpdateIsOK(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{
			"workerId": "worker-a",
			"status": "AVAILABLE",
			"lastHeartbeat": "2026-08-25T21:00:00Z",
			"registeredAt": "2026-08-25T20:00:00Z",
			"updatedAt": "2026-08-25T21:00:00Z"
		}`)
	}))
	defer server.Close()
	resp, err := New(server.URL, 5*time.Second).RegisterWorker(context.Background(), model.RegisterWorkerRequest{WorkerID: "worker-a"})
	if err != nil {
		t.Fatal(err)
	}
	if resp.WorkerID != "worker-a" {
		t.Fatalf("resp=%+v", resp)
	}
}

func TestRegisterWorkerFailure(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = io.WriteString(w, `{"code":"INVALID_REGISTRATION"}`)
	}))
	defer server.Close()
	_, err := New(server.URL, 5*time.Second).RegisterWorker(context.Background(), model.RegisterWorkerRequest{WorkerID: "worker-a"})
	if err == nil {
		t.Fatal("expected error")
	}
	var statusErr *StatusError
	if !errors.As(err, &statusErr) || statusErr.Status != http.StatusBadRequest {
		t.Fatalf("err=%v", err)
	}
}

func TestHeartbeatSuccess(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/internal/workers/worker-a/heartbeat" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		if r.ContentLength > 0 {
			t.Fatalf("expected empty body, content-length=%d", r.ContentLength)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{
			"workerId": "worker-a",
			"status": "AVAILABLE",
			"lastHeartbeat": "2026-08-25T22:00:00Z"
		}`)
	}))
	defer server.Close()

	resp, err := New(server.URL, 5*time.Second).Heartbeat(context.Background(), "worker-a")
	if err != nil {
		t.Fatal(err)
	}
	if resp.WorkerID != "worker-a" || resp.Status != "AVAILABLE" {
		t.Fatalf("resp=%+v", resp)
	}
	if resp.LastHeartbeat.IsZero() {
		t.Fatal("expected lastHeartbeat")
	}
}

func TestHeartbeatUnknownWorker(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/workers/missing/heartbeat" {
			t.Fatalf("path=%s", r.URL.Path)
		}
		w.WriteHeader(http.StatusNotFound)
		_, _ = io.WriteString(w, `{"code":"WORKER_NOT_FOUND"}`)
	}))
	defer server.Close()

	_, err := New(server.URL, 5*time.Second).Heartbeat(context.Background(), "missing")
	if err == nil {
		t.Fatal("expected error")
	}
	var statusErr *StatusError
	if !errors.As(err, &statusErr) || statusErr.Status != http.StatusNotFound {
		t.Fatalf("err=%v", err)
	}
}
