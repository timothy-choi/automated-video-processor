package executor

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"strconv"
	"strings"

	"github.com/timothy-choi/automated-video-processor/worker/internal/model"
)

type probeOutput struct {
	Format  probeFormat   `json:"format"`
	Streams []probeStream `json:"streams"`
}

type probeFormat struct {
	FormatName string `json:"format_name"`
	Duration   string `json:"duration"`
	Size       string `json:"size"`
}

type probeStream struct {
	CodecType    string `json:"codec_type"`
	CodecName    string `json:"codec_name"`
	Width        int    `json:"width"`
	Height       int    `json:"height"`
	RFrameRate   string `json:"r_frame_rate"`
	AvgFrameRate string `json:"avg_frame_rate"`
}

func ProbeFile(ctx context.Context, ffprobePath, inputPath string) (model.MetadataResult, error) {
	cmd := exec.CommandContext(ctx, ffprobePath,
		"-v", "error",
		"-show_format",
		"-show_streams",
		"-of", "json",
		inputPath,
	)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		return model.MetadataResult{}, fmt.Errorf("ffprobe failed: %s", truncate(message, 4000))
	}

	return ParseProbeJSON(stdout.Bytes())
}

func ParseProbeJSON(raw []byte) (model.MetadataResult, error) {
	var probe probeOutput
	if err := json.Unmarshal(raw, &probe); err != nil {
		return model.MetadataResult{}, fmt.Errorf("ffprobe JSON is invalid: %w", err)
	}

	result := model.MetadataResult{}
	if probe.Format.FormatName != "" {
		name := probe.Format.FormatName
		result.FormatName = &name
	}
	if duration, ok := parseFloat(probe.Format.Duration); ok {
		result.DurationSeconds = &duration
	}
	if size, ok := parseInt64(probe.Format.Size); ok {
		result.SizeBytes = &size
	}

	for _, stream := range probe.Streams {
		switch stream.CodecType {
		case "video":
			if result.VideoCodec == nil && stream.CodecName != "" {
				codec := stream.CodecName
				result.VideoCodec = &codec
			}
			if result.Width == nil && stream.Width > 0 {
				width := stream.Width
				result.Width = &width
			}
			if result.Height == nil && stream.Height > 0 {
				height := stream.Height
				result.Height = &height
			}
			if result.FrameRate == nil {
				if rate, ok := parseFrameRate(stream.AvgFrameRate); ok {
					result.FrameRate = &rate
				} else if rate, ok := parseFrameRate(stream.RFrameRate); ok {
					result.FrameRate = &rate
				}
			}
		case "audio":
			if result.AudioCodec == nil && stream.CodecName != "" {
				codec := stream.CodecName
				result.AudioCodec = &codec
			}
		}
	}

	return result, nil
}

func parseFloat(raw string) (float64, bool) {
	if raw == "" {
		return 0, false
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return 0, false
	}
	return value, true
}

func parseInt64(raw string) (int64, bool) {
	if raw == "" {
		return 0, false
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return 0, false
	}
	return value, true
}

func parseFrameRate(raw string) (float64, bool) {
	if raw == "" || raw == "0/0" {
		return 0, false
	}
	numerator, denominator, found := strings.Cut(raw, "/")
	if !found {
		return parseFloat(raw)
	}
	num, errNum := strconv.ParseFloat(numerator, 64)
	den, errDen := strconv.ParseFloat(denominator, 64)
	if errNum != nil || errDen != nil || den == 0 {
		return 0, false
	}
	return num / den, true
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max]
}
