package storage

import "context"

type ObjectStore interface {
	Download(ctx context.Context, bucket, key, destPath string) error
	Upload(ctx context.Context, bucket, key, srcPath, contentType string) error
}

func ThumbnailObjectKey(jobID, operationID string) string {
	return "jobs/" + jobID + "/operations/" + operationID + "/thumbnail.jpg"
}

func AudioObjectKey(jobID, operationID string) string {
	return "jobs/" + jobID + "/operations/" + operationID + "/audio.m4a"
}

func Transcode1080pObjectKey(jobID, operationID string) string {
	return "jobs/" + jobID + "/operations/" + operationID + "/video-1080p.mp4"
}

func AV1ObjectKey(jobID, operationID string) string {
	return "jobs/" + jobID + "/operations/" + operationID + "/video-av1.mp4"
}

func ObjectURI(bucket, key string) string {
	return "s3://" + bucket + "/" + key
}
