package inputuri

import (
	"strings"
	"testing"
)

func TestPathFromFileURI(t *testing.T) {
	path, err := PathFromFileURI("file:///tmp/sample.mp4")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if path != "/tmp/sample.mp4" {
		t.Fatalf("got %q, want /tmp/sample.mp4", path)
	}
}

func TestPathFromFileURIRejectsS3(t *testing.T) {
	_, err := PathFromFileURI("s3://media-input/video.mp4")
	if err == nil {
		t.Fatal("expected error for s3 URI")
	}
}

func TestPathFromFileURIRejectsEmpty(t *testing.T) {
	if _, err := PathFromFileURI("   "); err == nil {
		t.Fatal("expected error for empty URI")
	}
}

func TestParseS3URI(t *testing.T) {
	bucket, key, err := ParseS3URI("s3://media-input/video.mp4")
	if err != nil {
		t.Fatal(err)
	}
	if bucket != "media-input" || key != "video.mp4" {
		t.Fatalf("got %s/%s", bucket, key)
	}
}

func TestParseS3URINestedKey(t *testing.T) {
	bucket, key, err := ParseS3URI("s3://media-input/path/to/video.mp4")
	if err != nil {
		t.Fatal(err)
	}
	if bucket != "media-input" || key != "path/to/video.mp4" {
		t.Fatalf("got %s/%s", bucket, key)
	}
}

func TestParseS3URIRejectsMalformed(t *testing.T) {
	cases := []string{
		"s3://",
		"s3:///video.mp4",
		"s3://bucket",
		"s3://bucket/",
	}
	for _, raw := range cases {
		if _, _, err := ParseS3URI(raw); err == nil {
			t.Fatalf("expected error for %q", raw)
		}
	}
}

func TestParseUnsupportedScheme(t *testing.T) {
	_, err := Parse("https://example.com/video.mp4")
	if err == nil || !strings.Contains(err.Error(), "file://") {
		t.Fatalf("error should mention supported schemes, got %v", err)
	}
}
