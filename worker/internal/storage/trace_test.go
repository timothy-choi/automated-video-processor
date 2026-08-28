package storage

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"go.opentelemetry.io/otel"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
)

func TestTracedStoreEmitsDownloadAndUploadSpans(t *testing.T) {
	exp := tracetest.NewInMemoryExporter()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSyncer(exp), sdktrace.WithSampler(sdktrace.AlwaysSample()))
	otel.SetTracerProvider(tp)
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })

	mem := NewMemoryStore()
	mem.Put("in", "clip.mp4", []byte("abc"))
	store := Traced(mem)
	dir := t.TempDir()
	dest := filepath.Join(dir, "in.bin")
	if err := store.Download(context.Background(), "in", "clip.mp4", dest); err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(dir, "out.bin")
	if err := os.WriteFile(src, []byte("xyz"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := store.Upload(context.Background(), "out", "clip.mp4", src, "video/mp4"); err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, span := range exp.GetSpans() {
		names[span.Name] = true
	}
	if !names["objectstore.download"] || !names["objectstore.upload"] {
		t.Fatalf("spans=%v", names)
	}
}

func TestTracedStoreExportFailureDoesNotFailIO(t *testing.T) {
	mem := NewMemoryStore()
	mem.Put("in", "clip.mp4", []byte("abc"))
	dest := filepath.Join(t.TempDir(), "in.bin")
	if err := Traced(mem).Download(context.Background(), "in", "clip.mp4", dest); err != nil {
		t.Fatal(err)
	}
}
