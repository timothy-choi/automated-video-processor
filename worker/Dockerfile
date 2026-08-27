# syntax=docker/dockerfile:1
FROM golang:1.25-bookworm AS builder
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/worker ./cmd/worker

# FFmpeg from Debian so advertised encoders match what is actually installed.
# Build fails if libx264 or a software AV1 encoder is missing.
FROM debian:bookworm-slim
RUN apt-get update \
	&& apt-get install -y --no-install-recommends ca-certificates ffmpeg \
	&& rm -rf /var/lib/apt/lists/* \
	&& ffmpeg -hide_banner -encoders 2>/dev/null | grep -E 'libx264' \
	&& ffmpeg -hide_banner -encoders 2>/dev/null | grep -E 'libsvtav1|libaom-av1' \
	&& groupadd --system worker \
	&& useradd --system --gid worker --home-dir /work --create-home worker
COPY --from=builder /out/worker /usr/local/bin/worker
WORKDIR /work
USER worker
ENTRYPOINT ["/usr/local/bin/worker"]
