package inputuri

import (
	"fmt"
	"net/url"
	"strings"
)

type Kind string

const (
	KindFile Kind = "file"
	KindS3   Kind = "s3"
)

type Ref struct {
	Kind   Kind
	Raw    string
	Path   string
	Bucket string
	Key    string
}

func Parse(raw string) (Ref, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return Ref{}, fmt.Errorf("input URI is empty")
	}
	parsed, err := url.Parse(trimmed)
	if err != nil {
		return Ref{}, fmt.Errorf("input URI is not valid: %w", err)
	}
	switch strings.ToLower(parsed.Scheme) {
	case "file":
		path, err := pathFromFileURL(parsed)
		if err != nil {
			return Ref{}, err
		}
		return Ref{Kind: KindFile, Raw: trimmed, Path: path}, nil
	case "s3":
		bucket, key, err := parseS3URL(parsed)
		if err != nil {
			return Ref{}, err
		}
		return Ref{Kind: KindS3, Raw: trimmed, Bucket: bucket, Key: key}, nil
	default:
		scheme := parsed.Scheme
		if scheme == "" {
			scheme = "(none)"
		}
		return Ref{}, fmt.Errorf("unsupported URI scheme %s; Phase 2C supports file:// and s3://", scheme)
	}
}

func PathFromFileURI(raw string) (string, error) {
	ref, err := Parse(raw)
	if err != nil {
		return "", err
	}
	if ref.Kind != KindFile {
		return "", fmt.Errorf("unsupported URI scheme %s; expected file://", ref.Kind)
	}
	return ref.Path, nil
}

func ParseS3URI(raw string) (bucket, key string, err error) {
	ref, err := Parse(raw)
	if err != nil {
		return "", "", err
	}
	if ref.Kind != KindS3 {
		return "", "", fmt.Errorf("not an s3 URI")
	}
	return ref.Bucket, ref.Key, nil
}

func pathFromFileURL(parsed *url.URL) (string, error) {
	if parsed.Host != "" && parsed.Host != "localhost" {
		return "", fmt.Errorf("file URI host %q is not supported", parsed.Host)
	}
	path := parsed.Path
	if path == "" {
		return "", fmt.Errorf("file URI is missing a path")
	}
	return path, nil
}

func parseS3URL(parsed *url.URL) (string, string, error) {
	bucket := parsed.Host
	if bucket == "" {
		return "", "", fmt.Errorf("S3 URI is missing a bucket")
	}
	key := strings.TrimPrefix(parsed.Path, "/")
	if key == "" {
		return "", "", fmt.Errorf("S3 URI is missing an object key")
	}
	return bucket, key, nil
}
