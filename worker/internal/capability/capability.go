package capability

import (
	"context"
	"fmt"
	"os"
	"runtime"
	"sort"
	"strings"

	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
)

const (
	OperationMetadata        = "METADATA"
	OperationThumbnail       = "THUMBNAIL"
	OperationAudioExtraction = "AUDIO_EXTRACTION"
	OperationTranscode1080P  = "TRANSCODE_1080P"
)

type Snapshot struct {
	Hostname            string
	CPUArchitecture     string
	CPUCores            int
	MemoryBytes         int64
	FFmpegVersion       string
	SupportedCodecs     []string
	SupportedOperations []string
}

type Probe struct {
	FFmpegPath            string
	FfprobePath           string
	Hostname              string
	ImplementedOperations []string
	RestrictOperations    []string
	Arch                  string
	Cores                 int
	MemoryBytes           int64
	Command               func(ctx context.Context, name string, args ...string) (string, error)
	Memory                func() (int64, error)
}

func ImplementedOperations() []string {
	return []string{OperationMetadata, OperationThumbnail, OperationAudioExtraction, OperationTranscode1080P}
}

func Detect(ctx context.Context, probe Probe) (Snapshot, error) {
	hostname := strings.TrimSpace(probe.Hostname)
	if hostname == "" {
		var err error
		hostname, err = os.Hostname()
		if err != nil || hostname == "" {
			return Snapshot{}, fmt.Errorf("hostname is required for registration")
		}
	}

	implemented := probe.ImplementedOperations
	if len(implemented) == 0 {
		implemented = ImplementedOperations()
	}
	operations, err := RestrictOperations(implemented, probe.RestrictOperations)
	if err != nil {
		return Snapshot{}, err
	}

	arch := probe.Arch
	if arch == "" {
		arch = runtime.GOARCH
	}
	cores := probe.Cores
	if cores <= 0 {
		cores = runtime.NumCPU()
	}
	if cores <= 0 {
		return Snapshot{}, fmt.Errorf("cpuCores must be greater than 0")
	}

	memory := probe.MemoryBytes
	if memory <= 0 {
		lookup := probe.Memory
		if lookup == nil {
			lookup = systemMemoryBytes
		}
		memory, err = lookup()
		if err != nil {
			return Snapshot{}, fmt.Errorf("detect memory: %w", err)
		}
	}
	if memory < 0 {
		return Snapshot{}, fmt.Errorf("memoryBytes must be greater than or equal to 0")
	}

	ffmpegPath := probe.FFmpegPath
	if ffmpegPath == "" {
		ffmpegPath = "ffmpeg"
	}
	ffprobePath := probe.FfprobePath
	if ffprobePath == "" {
		ffprobePath = "ffprobe"
	}
	run := probe.Command
	if run == nil {
		run = runCommand
	}

	_, ffprobeErr := run(ctx, ffprobePath, "-version")
	ffmpegOutput, ffmpegErr := run(ctx, ffmpegPath, "-version")
	ffprobeOK := ffprobeErr == nil
	ffmpegOK := ffmpegErr == nil

	version := ""
	codecs := []string{}
	encoderOutput := ""
	if ffmpegOK {
		version = ParseFFmpegVersion(ffmpegOutput)
		out, encoderErr := run(ctx, ffmpegPath, "-encoders")
		if encoderErr == nil {
			encoderOutput = out
			codecs = ParseSupportedCodecs(encoderOutput)
		}
	}

	advertised, err := advertiseOperations(operations, ffprobeOK, ffmpegOK, encoderOutput)
	if err != nil {
		return Snapshot{}, err
	}

	return Snapshot{
		Hostname:            hostname,
		CPUArchitecture:     arch,
		CPUCores:            cores,
		MemoryBytes:         memory,
		FFmpegVersion:       version,
		SupportedCodecs:     codecs,
		SupportedOperations: advertised,
	}, nil
}

func advertiseOperations(configured []string, ffprobeOK, ffmpegOK bool, encoderOutput string) ([]string, error) {
	var advertised []string
	for _, op := range configured {
		switch op {
		case OperationMetadata:
			if !ffprobeOK {
				return nil, fmt.Errorf("METADATA requires ffprobe")
			}
			advertised = append(advertised, op)
		case OperationThumbnail:
			if !ffmpegOK {
				return nil, fmt.Errorf("THUMBNAIL requires ffmpeg")
			}
			advertised = append(advertised, op)
		case OperationAudioExtraction:
			if !ffmpegOK {
				return nil, fmt.Errorf("AUDIO_EXTRACTION requires ffmpeg")
			}
			advertised = append(advertised, op)
		case OperationTranscode1080P:
			if !ffmpegOK {
				return nil, fmt.Errorf("TRANSCODE_1080P requires ffmpeg")
			}
			if !HasEncoder(encoderOutput, "libx264") {
				return nil, fmt.Errorf("TRANSCODE_1080P requires H.264 encoder (libx264)")
			}
			advertised = append(advertised, op)
		default:
			return nil, fmt.Errorf("cannot advertise unimplemented type %s", op)
		}
	}
	if len(advertised) == 0 {
		return nil, fmt.Errorf("no executable operations available")
	}
	return advertised, nil
}

func RestrictOperations(implemented, requested []string) ([]string, error) {
	impl := uniquePreserve(implemented)
	if len(impl) == 0 {
		return nil, fmt.Errorf("worker implements no operations")
	}
	if len(requested) == 0 {
		return impl, nil
	}
	allowed := map[string]struct{}{}
	for _, op := range impl {
		allowed[op] = struct{}{}
	}
	var restricted []string
	seen := map[string]struct{}{}
	for _, raw := range requested {
		op := strings.TrimSpace(raw)
		if op == "" {
			continue
		}
		if _, ok := allowed[op]; !ok {
			return nil, fmt.Errorf("SUPPORTED_OPERATIONS cannot advertise unimplemented type %s", op)
		}
		if _, dup := seen[op]; dup {
			continue
		}
		seen[op] = struct{}{}
		restricted = append(restricted, op)
	}
	if len(restricted) == 0 {
		return nil, fmt.Errorf("SUPPORTED_OPERATIONS is empty")
	}
	return restricted, nil
}

func ParseSupportedOperationsEnv(raw string) ([]string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}
	var requested []string
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		requested = append(requested, part)
	}
	return RestrictOperations(ImplementedOperations(), requested)
}

func RegistrationRequest(workerID string, snap Snapshot) model.RegisterWorkerRequest {
	return model.RegisterWorkerRequest{
		WorkerID:            workerID,
		Hostname:            snap.Hostname,
		SupportedOperations: append([]string(nil), snap.SupportedOperations...),
		SupportedCodecs:     append([]string(nil), snap.SupportedCodecs...),
		CPUArchitecture:     snap.CPUArchitecture,
		CPUCores:            snap.CPUCores,
		MemoryBytes:         snap.MemoryBytes,
		FFmpegVersion:       snap.FFmpegVersion,
	}
}

func uniquePreserve(values []string) []string {
	seen := map[string]struct{}{}
	var out []string
	for _, raw := range values {
		value := strings.TrimSpace(raw)
		if value == "" {
			continue
		}
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		out = append(out, value)
	}
	return out
}

func sorted(values []string) []string {
	out := append([]string(nil), values...)
	sort.Strings(out)
	return out
}
