package storage

import (
	"context"
	"fmt"
	"os"
	"sync"
)

type MemoryStore struct {
	mu          sync.Mutex
	objects     map[string][]byte
	DownloadErr error
	UploadErr   error
}

func NewMemoryStore() *MemoryStore {
	return &MemoryStore{objects: map[string][]byte{}}
}

func (m *MemoryStore) Put(bucket, key string, data []byte) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.objects[id(bucket, key)] = append([]byte(nil), data...)
}

func (m *MemoryStore) Get(bucket, key string) ([]byte, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	data, ok := m.objects[id(bucket, key)]
	if !ok {
		return nil, false
	}
	return append([]byte(nil), data...), true
}

func (m *MemoryStore) Download(ctx context.Context, bucket, key, destPath string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if m.DownloadErr != nil {
		return m.DownloadErr
	}
	m.mu.Lock()
	data, ok := m.objects[id(bucket, key)]
	m.mu.Unlock()
	if !ok {
		return fmt.Errorf("download s3://%s/%s failed: object not found", bucket, key)
	}
	return os.WriteFile(destPath, data, 0o600)
}

func (m *MemoryStore) Upload(ctx context.Context, bucket, key, srcPath, contentType string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if m.UploadErr != nil {
		return m.UploadErr
	}
	_ = contentType
	data, err := os.ReadFile(srcPath)
	if err != nil {
		return fmt.Errorf("upload s3://%s/%s failed: %w", bucket, key, err)
	}
	m.Put(bucket, key, data)
	return nil
}

func id(bucket, key string) string {
	return bucket + "/" + key
}
