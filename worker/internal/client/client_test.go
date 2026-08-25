package client

import (
	"context"
	"encoding/json"
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
