package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/smithy-go"
)

type Config struct {
	Endpoint       string
	Region         string
	AccessKey      string
	SecretKey      string
	ForcePathStyle bool
}

type S3Store struct {
	client *s3.Client
}

func NewS3Store(ctx context.Context, cfg Config) (*S3Store, error) {
	region := cfg.Region
	if region == "" {
		region = "us-east-1"
	}
	awsCfg, err := config.LoadDefaultConfig(ctx,
		config.WithRegion(region),
		config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(cfg.AccessKey, cfg.SecretKey, "")),
	)
	if err != nil {
		return nil, fmt.Errorf("object store config failed")
	}
	client := s3.NewFromConfig(awsCfg, func(o *s3.Options) {
		if cfg.Endpoint != "" {
			o.BaseEndpoint = aws.String(cfg.Endpoint)
		}
		o.UsePathStyle = cfg.ForcePathStyle
	})
	return &S3Store{client: client}, nil
}

func (s *S3Store) Download(ctx context.Context, bucket, key, destPath string) error {
	out, err := s.client.GetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key),
	})
	if err != nil {
		return fmt.Errorf("download s3://%s/%s failed: %s", bucket, key, classify(err))
	}
	defer out.Body.Close()

	file, err := os.Create(destPath)
	if err != nil {
		return fmt.Errorf("download s3://%s/%s failed: cannot create local file", bucket, key)
	}
	defer file.Close()

	if _, err := io.Copy(file, out.Body); err != nil {
		_ = os.Remove(destPath)
		return fmt.Errorf("download s3://%s/%s failed: cannot write local file", bucket, key)
	}
	return nil
}

func (s *S3Store) Upload(ctx context.Context, bucket, key, srcPath, contentType string) error {
	file, err := os.Open(srcPath)
	if err != nil {
		return fmt.Errorf("upload s3://%s/%s failed: cannot read local file", bucket, key)
	}
	defer file.Close()

	input := &s3.PutObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key),
		Body:   file,
	}
	if contentType != "" {
		input.ContentType = aws.String(contentType)
	}
	if _, err := s.client.PutObject(ctx, input); err != nil {
		return fmt.Errorf("upload s3://%s/%s failed: %s", bucket, key, classify(err))
	}
	return nil
}

func classify(err error) string {
	var apiErr smithy.APIError
	if errors.As(err, &apiErr) {
		switch apiErr.ErrorCode() {
		case "NoSuchKey", "NotFound":
			return "object not found"
		case "NoSuchBucket":
			return "bucket not found"
		case "InvalidAccessKeyId", "SignatureDoesNotMatch", "AccessDenied":
			return "credentials rejected"
		}
	}
	return "object store request failed"
}
