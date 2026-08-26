package capability

import (
	"bytes"
	"context"
	"os/exec"
	"regexp"
	"strings"
)

var ffmpegVersionPattern = regexp.MustCompile(`(?i)^ffmpeg version ([^\s]+)`)

var encoderNameToCodec = []struct {
	match string
	codec string
}{
	{"libaom-av1", "av1"},
	{"libsvtav1", "av1"},
	{"librav1e", "av1"},
	{"libx265", "hevc"},
	{"libx264", "h264"},
	{"libvpx-vp9", "vp9"},
}

func ParseFFmpegVersion(output string) string {
	for _, line := range strings.Split(output, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		matches := ffmpegVersionPattern.FindStringSubmatch(line)
		if len(matches) == 2 {
			return strings.TrimRight(matches[1], ",")
		}
	}
	return ""
}

func HasEncoder(output, encoder string) bool {
	want := strings.ToLower(strings.TrimSpace(encoder))
	if want == "" {
		return false
	}
	for _, line := range strings.Split(output, "\n") {
		if strings.ToLower(encoderName(line)) == want {
			return true
		}
	}
	return false
}

// SelectAV1Encoder returns the encoder the H264_TO_AV1 executor will use:
// libsvtav1 if present, otherwise libaom-av1. librav1e and hardware AV1
// encoders are not selected in this phase.
func SelectAV1Encoder(output string) string {
	if HasEncoder(output, EncoderLibSvtAV1) {
		return EncoderLibSvtAV1
	}
	if HasEncoder(output, EncoderLibAomAV1) {
		return EncoderLibAomAV1
	}
	return ""
}

func ParseSupportedCodecs(output string) []string {
	found := map[string]struct{}{}
	for _, line := range strings.Split(output, "\n") {
		name := encoderName(line)
		if name == "" {
			continue
		}
		if codec := canonicalizeCodec(name); codec != "" {
			found[codec] = struct{}{}
		}
	}
	var codecs []string
	for codec := range found {
		codecs = append(codecs, codec)
	}
	return sorted(codecs)
}

func encoderName(line string) string {
	fields := strings.Fields(strings.TrimSpace(line))
	if len(fields) < 2 {
		return ""
	}
	switch fields[0][0] {
	case 'V', 'A', 'S':
		return fields[1]
	default:
		return ""
	}
}

func canonicalizeCodec(encoder string) string {
	name := strings.ToLower(strings.TrimSpace(encoder))
	for _, mapping := range encoderNameToCodec {
		if name == mapping.match || strings.HasPrefix(name, mapping.match) {
			return mapping.codec
		}
	}
	switch {
	case name == "h264" || strings.HasPrefix(name, "h264_") || strings.HasSuffix(name, "_h264"):
		return "h264"
	case name == "hevc" || strings.HasPrefix(name, "hevc_") || strings.HasSuffix(name, "_hevc"):
		return "hevc"
	case name == "av1" || strings.HasPrefix(name, "av1_") || strings.HasSuffix(name, "_av1"):
		return "av1"
	case name == "vp9" || strings.HasPrefix(name, "vp9_") || strings.HasSuffix(name, "_vp9"):
		return "vp9"
	default:
		return ""
	}
}

func runCommand(ctx context.Context, name string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	out := strings.TrimSpace(stdout.String())
	if out == "" {
		out = strings.TrimSpace(stderr.String())
	} else if stderr.Len() > 0 {
		out = out + "\n" + strings.TrimSpace(stderr.String())
	}
	return out, err
}
