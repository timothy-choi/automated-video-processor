package capability

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
)

func TestImplementedOperations(t *testing.T) {
	got := ImplementedOperations()
	if strings.Join(got, ",") != "METADATA,THUMBNAIL,AUDIO_EXTRACTION" {
		t.Fatalf("implemented=%v", got)
	}
}

func TestRestrictOperationsCannotAddUnsupported(t *testing.T) {
	if _, err := RestrictOperations(ImplementedOperations(), []string{"TRANSCODE_1080P"}); err == nil {
		t.Fatal("expected error")
	}
}

func TestRestrictOperationsAllowsSubset(t *testing.T) {
	got, err := RestrictOperations(ImplementedOperations(), []string{"METADATA", "METADATA"})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(got, ",") != "METADATA" {
		t.Fatalf("got=%v", got)
	}
}

func TestParseSupportedOperationsEnv(t *testing.T) {
	got, err := ParseSupportedOperationsEnv(" METADATA , THUMBNAIL ")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(got, ",") != "METADATA,THUMBNAIL" {
		t.Fatalf("got=%v", got)
	}
	got, err = ParseSupportedOperationsEnv("AUDIO_EXTRACTION")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(got, ",") != "AUDIO_EXTRACTION" {
		t.Fatalf("got=%v", got)
	}
	if _, err := ParseSupportedOperationsEnv("TRANSCODE_1080P"); err == nil {
		t.Fatal("expected unimplemented error")
	}
}

func TestParseFFmpegVersion(t *testing.T) {
	if got := ParseFFmpegVersion("ffmpeg version 8.1.2 Copyright (c) 2000-2025 the FFmpeg developers\nbuilt with Apple clang"); got != "8.1.2" {
		t.Fatalf("got=%s", got)
	}
	if got := ParseFFmpegVersion("ffmpeg version n7.1.1-4 Copyright (c) 2000-2024"); got != "n7.1.1-4" {
		t.Fatalf("got=%s", got)
	}
	if got := ParseFFmpegVersion("not ffmpeg"); got != "" {
		t.Fatalf("got=%s", got)
	}
}

func TestParseSupportedCodecs(t *testing.T) {
	output := `
Encoders:
 V..... = Video
 ------
 V..... libx264              H.264 / AVC
 V..... h264_videotoolbox    VideoToolbox H.264 Encoder
 V..... libx265              H.265 / HEVC
 V..... libaom-av1           Alliance for Open Media AV1
 V..... libvpx-vp9           libvpx VP9
 V..... mpeg4                MPEG-4 part 2
`
	got := ParseSupportedCodecs(output)
	if strings.Join(got, ",") != "av1,h264,hevc,vp9" {
		t.Fatalf("got=%v", got)
	}
}

func TestParseMeminfo(t *testing.T) {
	got, err := ParseMeminfo("MemTotal:       16777216 kB\nMemFree:        1 kB\n")
	if err != nil {
		t.Fatal(err)
	}
	if got != 16777216*1024 {
		t.Fatalf("got=%d", got)
	}
}

func TestDetectUsesProbeAndDoesNotFabricateCodecs(t *testing.T) {
	snap, err := Detect(context.Background(), Probe{
		FFmpegPath:         "ffmpeg",
		FfprobePath:        "ffprobe",
		Hostname:           "mac-worker-a",
		Arch:               "arm64",
		Cores:              8,
		MemoryBytes:        17179869184,
		RestrictOperations: []string{"METADATA"},
		Command:            fakeBinaries(true, true, " V..... libx264\n V..... mpeg4\n"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if snap.Hostname != "mac-worker-a" || snap.CPUArchitecture != "arm64" || snap.CPUCores != 8 {
		t.Fatalf("snap=%+v", snap)
	}
	if snap.FFmpegVersion != "7.1" {
		t.Fatalf("version=%s", snap.FFmpegVersion)
	}
	if strings.Join(snap.SupportedOperations, ",") != "METADATA" {
		t.Fatalf("operations=%v", snap.SupportedOperations)
	}
	if strings.Join(snap.SupportedCodecs, ",") != "h264" {
		t.Fatalf("codecs=%v", snap.SupportedCodecs)
	}
}

func TestDetectAdvertisesBothWhenBinariesAvailable(t *testing.T) {
	snap, err := Detect(context.Background(), Probe{
		Hostname:    "host",
		Arch:        "arm64",
		Cores:       4,
		MemoryBytes: 1024,
		Command:     fakeBinaries(true, true, " V..... libx264\n"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(snap.SupportedOperations, ",") != "METADATA,THUMBNAIL,AUDIO_EXTRACTION" {
		t.Fatalf("operations=%v", snap.SupportedOperations)
	}
}

func TestDetectFailsWhenFfprobeMissingForMetadata(t *testing.T) {
	_, err := Detect(context.Background(), Probe{
		Hostname:           "host",
		Arch:               "amd64",
		Cores:              2,
		MemoryBytes:        1024,
		RestrictOperations: []string{"METADATA"},
		Command:            fakeBinaries(false, true, ""),
	})
	if err == nil || !strings.Contains(err.Error(), "METADATA requires ffprobe") {
		t.Fatalf("err=%v", err)
	}
}

func TestDetectFailsWhenFFmpegMissingForThumbnail(t *testing.T) {
	_, err := Detect(context.Background(), Probe{
		Hostname:    "host",
		Arch:        "amd64",
		Cores:       2,
		MemoryBytes: 1024,
		Command:     fakeBinaries(true, false, ""),
	})
	if err == nil || !strings.Contains(err.Error(), "THUMBNAIL requires ffmpeg") {
		t.Fatalf("err=%v", err)
	}
}

func TestDetectFailsWhenFFmpegMissingForAudioExtraction(t *testing.T) {
	_, err := Detect(context.Background(), Probe{
		Hostname:           "host",
		Arch:               "amd64",
		Cores:              2,
		MemoryBytes:        1024,
		RestrictOperations: []string{"AUDIO_EXTRACTION"},
		Command:            fakeBinaries(true, false, ""),
	})
	if err == nil || !strings.Contains(err.Error(), "AUDIO_EXTRACTION requires ffmpeg") {
		t.Fatalf("err=%v", err)
	}
}

func TestDetectMetadataOnlySucceedsWithoutFFmpeg(t *testing.T) {
	snap, err := Detect(context.Background(), Probe{
		Hostname:           "host",
		Arch:               "amd64",
		Cores:              2,
		MemoryBytes:        1024,
		RestrictOperations: []string{"METADATA"},
		Command:            fakeBinaries(true, false, " V..... libx264\n"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(snap.SupportedOperations, ",") != "METADATA" {
		t.Fatalf("operations=%v", snap.SupportedOperations)
	}
	if snap.FFmpegVersion != "" || len(snap.SupportedCodecs) != 0 {
		t.Fatalf("snap=%+v", snap)
	}
}

func TestDetectRejectsUnsupportedConfiguredOperation(t *testing.T) {
	_, err := Detect(context.Background(), Probe{
		Hostname:           "host",
		Arch:               "amd64",
		Cores:              2,
		MemoryBytes:        1024,
		RestrictOperations: []string{"TRANSCODE_1080P"},
		Command:            fakeBinaries(true, true, ""),
	})
	if err == nil || !strings.Contains(err.Error(), "unimplemented") {
		t.Fatalf("err=%v", err)
	}
}

func fakeBinaries(ffprobeOK, ffmpegOK bool, encoders string) func(context.Context, string, ...string) (string, error) {
	return func(ctx context.Context, name string, args ...string) (string, error) {
		switch name {
		case "ffprobe":
			if !ffprobeOK {
				return "", errors.New("ffprobe: executable file not found")
			}
			return "ffprobe version 7.1", nil
		case "ffmpeg":
			if !ffmpegOK {
				return "", errors.New("ffmpeg: executable file not found")
			}
			if len(args) == 1 && args[0] == "-encoders" {
				return encoders, nil
			}
			return "ffmpeg version 7.1 Copyright (c) 2000-2024", nil
		default:
			return "", fmt.Errorf("unexpected binary %s", name)
		}
	}
}

func TestRegistrationRequest(t *testing.T) {
	req := RegistrationRequest("worker-a", Snapshot{
		Hostname:            "mac-worker-a",
		CPUArchitecture:     "arm64",
		CPUCores:            8,
		MemoryBytes:         99,
		FFmpegVersion:       "7.1",
		SupportedCodecs:     []string{"h264"},
		SupportedOperations: []string{"METADATA", "THUMBNAIL"},
	})
	if req.WorkerID != "worker-a" || req.CPUCores != 8 || req.MemoryBytes != 99 {
		t.Fatalf("req=%+v", req)
	}
}
