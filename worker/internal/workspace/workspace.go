package workspace

import (
	"os"
	"path/filepath"
	"strings"
	"unicode"
)

type Workspace struct {
	Dir string
}

func New(operationID string) (*Workspace, error) {
	dir, err := os.MkdirTemp("", "media-worker-"+sanitize(operationID)+"-")
	if err != nil {
		return nil, err
	}
	return &Workspace{Dir: dir}, nil
}

func (w *Workspace) File(name string) string {
	return filepath.Join(w.Dir, name)
}

func (w *Workspace) Cleanup() error {
	if w == nil || w.Dir == "" {
		return nil
	}
	return os.RemoveAll(w.Dir)
}

func sanitize(value string) string {
	var b strings.Builder
	for _, r := range value {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || r == '-' {
			b.WriteRune(r)
		}
		if b.Len() >= 12 {
			break
		}
	}
	if b.Len() == 0 {
		return "op"
	}
	return b.String()
}
