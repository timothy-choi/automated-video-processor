package storage

import (
	"context"
	"os"

	"github.com/timothy-choi/automated-video-processor/worker/internal/otelx"
	"go.opentelemetry.io/otel/attribute"
)

type tracedStore struct {
	inner ObjectStore
}

func Traced(store ObjectStore) ObjectStore {
	if store == nil {
		return nil
	}
	return tracedStore{inner: store}
}

func (t tracedStore) Download(ctx context.Context, bucket, key, destPath string) error {
	ctx, span := otelx.Tracer().Start(ctx, "objectstore.download")
	defer span.End()
	span.SetAttributes(
		attribute.String("media.objectstore.bucket", bucket),
		attribute.String("media.objectstore.operation", "download"),
	)
	err := t.inner.Download(ctx, bucket, key, destPath)
	if err != nil {
		otelx.RecordError(span, err)
		return err
	}
	if info, statErr := os.Stat(destPath); statErr == nil {
		span.SetAttributes(attribute.Int64("media.objectstore.size_bytes", info.Size()))
	}
	return nil
}

func (t tracedStore) Upload(ctx context.Context, bucket, key, srcPath, contentType string) error {
	ctx, span := otelx.Tracer().Start(ctx, "objectstore.upload")
	defer span.End()
	span.SetAttributes(
		attribute.String("media.objectstore.bucket", bucket),
		attribute.String("media.objectstore.operation", "upload"),
	)
	if info, statErr := os.Stat(srcPath); statErr == nil {
		span.SetAttributes(attribute.Int64("media.objectstore.size_bytes", info.Size()))
	}
	err := t.inner.Upload(ctx, bucket, key, srcPath, contentType)
	if err != nil {
		otelx.RecordError(span, err)
	}
	return err
}
