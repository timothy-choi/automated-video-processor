package storage

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestThumbnailObjectKey(t *testing.T) {
	got := ThumbnailObjectKey("job-1", "op-2")
	want := "jobs/job-1/operations/op-2/thumbnail.jpg"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestAudioObjectKey(t *testing.T) {
	got := AudioObjectKey("job-1", "op-2")
	want := "jobs/job-1/operations/op-2/audio.m4a"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestObjectURI(t *testing.T) {
	got := ObjectURI("media-output", "jobs/job-1/operations/op-2/thumbnail.jpg")
	want := "s3://media-output/jobs/job-1/operations/op-2/thumbnail.jpg"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestSHA256File(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "thumb.jpg")
	data := []byte("jpeg-bytes")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	sum, size, err := SHA256File(path)
	if err != nil {
		t.Fatal(err)
	}
	if size != int64(len(data)) {
		t.Fatalf("size=%d", size)
	}
	want := FormatSHA256(sha256Sum(data))
	if sum != want {
		t.Fatalf("checksum=%s want %s", sum, want)
	}
}

func TestMemoryStoreDownloadUpload(t *testing.T) {
	store := NewMemoryStore()
	store.Put("media-input", "video.mp4", []byte("video-bytes"))
	dir := t.TempDir()
	dest := filepath.Join(dir, "in.mp4")
	if err := store.Download(context.Background(), "media-input", "video.mp4", dest); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(dest)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "video-bytes" {
		t.Fatalf("downloaded %q", got)
	}

	src := filepath.Join(dir, "out.jpg")
	if err := os.WriteFile(src, []byte("thumb"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := store.Upload(context.Background(), "media-output", "jobs/x/thumbnail.jpg", src, "image/jpeg"); err != nil {
		t.Fatal(err)
	}
	uploaded, ok := store.Get("media-output", "jobs/x/thumbnail.jpg")
	if !ok || string(uploaded) != "thumb" {
		t.Fatalf("uploaded=%q ok=%v", uploaded, ok)
	}
}

func TestMemoryStoreMissingObject(t *testing.T) {
	store := NewMemoryStore()
	err := store.Download(context.Background(), "media-input", "missing.mp4", filepath.Join(t.TempDir(), "x"))
	if err == nil || !bytes.Contains([]byte(err.Error()), []byte("object not found")) {
		t.Fatalf("expected object not found, got %v", err)
	}
}

func TestMemoryStorePropagatesConfiguredErrors(t *testing.T) {
	store := NewMemoryStore()
	store.DownloadErr = errors.New("download s3://media-input/video.mp4 failed: credentials rejected")
	store.UploadErr = errors.New("upload s3://media-output/x failed: object store request failed")
	if err := store.Download(context.Background(), "media-input", "video.mp4", filepath.Join(t.TempDir(), "x")); err == nil {
		t.Fatal("expected download error")
	}
	src := filepath.Join(t.TempDir(), "out.jpg")
	if err := os.WriteFile(src, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := store.Upload(context.Background(), "media-output", "x", src, "image/jpeg"); err == nil {
		t.Fatal("expected upload error")
	}
}

func sha256Sum(data []byte) []byte {
	sum := sha256.Sum256(data)
	return sum[:]
}
