package client

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
)

const testServiceToken = "test-worker-token"

func testClient(url string) *Client {
	return New(url, 5*time.Second, testServiceToken)
}

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

	claimed, ok, err := testClient(server.URL).Claim(context.Background())
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

	claimed, ok, err := testClient(server.URL).Claim(context.Background())
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
		if body["attemptId"] != "attempt-1" {
			t.Fatalf("attemptId = %v", body["attemptId"])
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
	err := testClient(server.URL).Complete(context.Background(), "op-1", model.CompleteRequest{
		AttemptID:       "attempt-1",
		ActualRuntimeMs: 12,
		Metadata: &model.MetadataResult{
			FormatName: &format,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestStartSendsWorkerIDAndParsesAttempt(t *testing.T) {
	var gotBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/internal/operations/op-9/start" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Fatalf("content-type=%s", r.Header.Get("Content-Type"))
		}
		if err := json.NewDecoder(r.Body).Decode(&gotBody); err != nil {
			t.Fatal(err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{
			"outcome": "STARTED",
			"operationId": "op-9",
			"jobId": "job-9",
			"type": "METADATA",
			"inputUri": "s3://media-input/sample.mp4",
			"status": "RUNNING",
			"attemptId": "attempt-9",
			"workerId": "worker-a",
			"leaseExpiresAt": "2026-08-25T18:00:30Z"
		}`)
	}))
	defer server.Close()

	started, err := testClient(server.URL).Start(context.Background(), "op-9", "worker-a", "33333333-3333-3333-3333-333333333333")
	if err != nil {
		t.Fatal(err)
	}
	if started.Outcome != model.StartStarted {
		t.Fatalf("outcome=%s", started.Outcome)
	}
	if started.AttemptID != "attempt-9" || started.WorkerID != "worker-a" {
		t.Fatalf("started=%+v", started)
	}
	if gotBody["workerId"] != "worker-a" {
		t.Fatalf("body=%v", gotBody)
	}
	if gotBody["assignmentId"] != "33333333-3333-3333-3333-333333333333" {
		t.Fatalf("body=%v", gotBody)
	}
}

func TestStartNotFoundIsStatusError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = io.WriteString(w, `{"code":"OPERATION_NOT_FOUND"}`)
	}))
	defer server.Close()

	_, err := testClient(server.URL).Start(context.Background(), "missing", "worker-a", "")
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

func TestRenewSendsWorkerAndAttempt(t *testing.T) {
	var gotBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/operations/op-9/attempts/attempt-9/renew" {
			t.Fatalf("path=%s", r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&gotBody); err != nil {
			t.Fatal(err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{
			"attemptId": "attempt-9",
			"workerId": "worker-a",
			"status": "RUNNING",
			"leaseExpiresAt": "2026-08-25T18:01:00Z"
		}`)
	}))
	defer server.Close()
	renewed, err := testClient(server.URL).Renew(context.Background(), "op-9", "attempt-9", "worker-a")
	if err != nil {
		t.Fatal(err)
	}
	if renewed.AttemptID != "attempt-9" {
		t.Fatalf("renewed=%+v", renewed)
	}
	if renewed.CancelRequested {
		t.Fatal("missing cancelRequested must default to false")
	}
	if gotBody["workerId"] != "worker-a" {
		t.Fatalf("body=%v", gotBody)
	}
}

func TestRenewParsesCancelRequested(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{
			"attemptId": "attempt-9",
			"workerId": "worker-a",
			"status": "RUNNING",
			"leaseExpiresAt": "2026-08-25T18:01:00Z",
			"cancelRequested": true
		}`)
	}))
	defer server.Close()
	renewed, err := testClient(server.URL).Renew(context.Background(), "op-9", "attempt-9", "worker-a")
	if err != nil {
		t.Fatal(err)
	}
	if !renewed.CancelRequested {
		t.Fatalf("renewed=%+v", renewed)
	}
}

func TestCancelledPostsAttemptPath(t *testing.T) {
	var got map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/internal/operations/op-1/attempts/attempt-1/cancelled" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatal(err)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"id":"op-1","status":"CANCELLED"}`)
	}))
	defer server.Close()
	if err := testClient(server.URL).Cancelled(context.Background(), "op-1", "attempt-1", "worker-a", 42); err != nil {
		t.Fatal(err)
	}
	if got["workerId"] != "worker-a" || got["actualRuntimeMs"].(float64) != 42 {
		t.Fatalf("body=%v", got)
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

	resp, err := testClient(server.URL).RegisterWorker(context.Background(), model.RegisterWorkerRequest{
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
	resp, err := testClient(server.URL).RegisterWorker(context.Background(), model.RegisterWorkerRequest{WorkerID: "worker-a"})
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
	_, err := testClient(server.URL).RegisterWorker(context.Background(), model.RegisterWorkerRequest{WorkerID: "worker-a"})
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

	resp, err := testClient(server.URL).Heartbeat(context.Background(), "worker-a")
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

func TestCompleteConflictIsConflict(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = io.WriteString(w, `{"code":"STALE_EXECUTION_ATTEMPT"}`)
	}))
	defer server.Close()
	err := testClient(server.URL).Complete(context.Background(), "op-1", model.CompleteRequest{AttemptID: "old"})
	if !IsConflict(err) {
		t.Fatalf("err=%v", err)
	}
}

func TestFailSendsAttemptID(t *testing.T) {
	var got map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/operations/op-1/fail" {
			t.Fatalf("path=%s", r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatal(err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	runtime := int64(9)
	if err := testClient(server.URL).Fail(context.Background(), "op-1", &runtime, "boom", "attempt-1"); err != nil {
		t.Fatal(err)
	}
	if got["attemptId"] != "attempt-1" || got["reason"] != "boom" {
		t.Fatalf("body=%v", got)
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

	_, err := testClient(server.URL).Heartbeat(context.Background(), "missing")
	if err == nil {
		t.Fatal("expected error")
	}
	var statusErr *StatusError
	if !errors.As(err, &statusErr) || statusErr.Status != http.StatusNotFound {
		t.Fatalf("err=%v", err)
	}
}

func TestInternalAuthHeaderOnLifecycleCalls(t *testing.T) {
	seen := map[string]string{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen[r.URL.Path] = r.Header.Get("Authorization")
		switch {
		case strings.HasSuffix(r.URL.Path, "/register"):
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"workerId":"worker-a","status":"AVAILABLE"}`)
		case strings.HasSuffix(r.URL.Path, "/heartbeat"):
			_, _ = io.WriteString(w, `{"workerId":"worker-a","status":"AVAILABLE","lastHeartbeat":"2026-08-27T00:00:00Z"}`)
		case strings.HasSuffix(r.URL.Path, "/start"):
			_, _ = io.WriteString(w, `{"outcome":"STARTED","attemptId":"attempt-1","workerId":"worker-a","leaseExpiresAt":"2026-08-27T00:00:30Z"}`)
		case strings.HasSuffix(r.URL.Path, "/renew"):
			_, _ = io.WriteString(w, `{"attemptId":"attempt-1","workerId":"worker-a","status":"RUNNING","leaseExpiresAt":"2026-08-27T00:00:30Z"}`)
		case strings.HasSuffix(r.URL.Path, "/claim"):
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer server.Close()

	c := testClient(server.URL)
	ctx := context.Background()
	if _, err := c.RegisterWorker(ctx, model.RegisterWorkerRequest{WorkerID: "worker-a"}); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Heartbeat(ctx, "worker-a"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Start(ctx, "op-1", "worker-a", "assign-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Renew(ctx, "op-1", "attempt-1", "worker-a"); err != nil {
		t.Fatal(err)
	}
	if err := c.Complete(ctx, "op-1", model.CompleteRequest{AttemptID: "attempt-1"}); err != nil {
		t.Fatal(err)
	}
	runtime := int64(1)
	if err := c.Fail(ctx, "op-1", &runtime, "boom", "attempt-1"); err != nil {
		t.Fatal(err)
	}
	if err := c.Cancelled(ctx, "op-1", "attempt-1", "worker-a", 1); err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.Claim(ctx); err != nil {
		t.Fatal(err)
	}
	if len(seen) == 0 {
		t.Fatal("expected authenticated calls")
	}
	for path, header := range seen {
		if header != "Bearer "+testServiceToken {
			t.Fatalf("path=%s authorization=%q", path, header)
		}
	}
}

func TestUnauthorizedIsAuthRejected(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"code":"UNAUTHORIZED","message":"Authentication required"}`)
	}))
	defer server.Close()
	err := testClient(server.URL).Complete(context.Background(), "op-1", model.CompleteRequest{AttemptID: "a"})
	if !IsUnauthorized(err) {
		t.Fatalf("err=%v", err)
	}
}

func TestForbiddenIsAuthRejected(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = io.WriteString(w, `{"code":"FORBIDDEN","message":"Forbidden"}`)
	}))
	defer server.Close()
	_, err := testClient(server.URL).Heartbeat(context.Background(), "worker-b")
	if !IsUnauthorized(err) {
		t.Fatalf("err=%v", err)
	}
}

func TestErrorsDoNotIncludeServiceToken(t *testing.T) {
	secret := "super-secret-worker-token-do-not-log"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"code":"UNAUTHORIZED"}`)
	}))
	defer server.Close()
	err := New(server.URL, time.Second, secret).Complete(context.Background(), "op-1", model.CompleteRequest{AttemptID: "a"})
	if err == nil {
		t.Fatal("expected error")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("token leaked in error: %v", err)
	}
}
