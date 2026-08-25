# Adaptive Distributed Media Processing Platform

This repository is evolving from the original **Automated Video Processor** into a new portfolio project:

**Adaptive Distributed Media Processing Platform** — a distributed system that will eventually schedule heterogeneous media-processing jobs across workers based on workload characteristics, worker resources, load, priority, and deadlines.

That later architecture (Go scheduler, RabbitMQ, FFmpeg workers, object storage, OpenTelemetry) is **not implemented yet**. This repository is currently at **Phase 2B**.

## Current status: Phase 2B — one Go worker, METADATA only

The canonical Java application is the Maven/Spring Boot project at:

```text
Server/drive
```

A single Go worker lives at:

```text
worker/
```

Phase 2B currently:

- accepts job submissions and persists `Job` + `Operation` records in PostgreSQL
- lets one Go worker claim a queued `METADATA` operation over an internal HTTP API
- runs **real ffprobe** against a local `file://` input
- records completion or failure, including parsed metadata and runtime

It does **not** schedule across workers, run other operation types, use RabbitMQ, or talk to object storage.

Stack: **Java 21**, **Spring Boot 4.1.1**, **Maven**, **PostgreSQL**, **Flyway**, **Spring Data JPA**, **Go**, **ffprobe**. The Maven `artifactId` remains `drive`.

## Build

From `Server/drive`:

```bash
./mvnw clean test
```

Requires **Java 21+**. Automated Java tests use **Testcontainers** and therefore need a running **Docker daemon**. Go tests (`cd worker && go test ./...`) do not need Docker. The Maven wrapper (`./mvnw`) is preferred over a system Maven install.

## Run tests

```bash
cd Server/drive
./mvnw clean test
```

```bash
cd worker
go test ./...
go vet ./...
```

Java tests start a temporary PostgreSQL container. They do **not** require the Compose database. One Go test generates a tiny clip with FFmpeg when `ffmpeg`/`ffprobe` are on `PATH`; it is skipped if they are not installed. GitHub Actions does **not** install FFmpeg.

## Local PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

This starts PostgreSQL 16 on port **5432** with database/user/password `media_platform`. Those values are **local development defaults**, not production secrets. If port 5432 is already in use (including a local Postgres install), set `POSTGRES_PORT`:

```bash
POSTGRES_PORT=55432 docker compose up -d postgres
DB_URL=jdbc:postgresql://localhost:55432/media_platform
```

Override connection settings with:

```text
DB_URL          default jdbc:postgresql://localhost:5432/media_platform
DB_USERNAME     default media_platform
DB_PASSWORD     default media_platform
```

## Start the application

```bash
docker compose up -d postgres
cd Server/drive
./mvnw spring-boot:run
```

The service listens on port **8080** by default. Override with `SERVER_PORT`:

```bash
SERVER_PORT=8081 ./mvnw spring-boot:run
```

## Health check

```bash
curl http://localhost:8080/health
```

Expected response:

```json
{"status":"UP"}
```

## Job API

Submit a job. Execution is not started; the job is stored as `QUEUED`.

```bash
curl -sS -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "inputUri": "s3://media-input/video.mp4",
    "operations": [
      {"type": "METADATA"},
      {"type": "THUMBNAIL"},
      {"type": "TRANSCODE_1080P"}
    ],
    "priority": "HIGH",
    "deadline": "2099-09-01T12:00:00Z"
  }'
```

Expected: **202 Accepted**, with `id`, `status: "QUEUED"`, timestamps, and the created operations.

```bash
curl -sS http://localhost:8080/jobs/<job-id>
curl -sS http://localhost:8080/jobs/<job-id>/operations
```

`priority` defaults to `NORMAL` when omitted. `deadline` is optional. Unknown jobs return **404**. Invalid bodies (missing `inputUri`, empty `operations`, unknown operation type, past deadline) return **400**.

`inputUri` is stored as a URI string. The public API does **not** contact S3 or verify that the object exists. **Phase 2B claims and executes `file://` URIs only.** A `METADATA` job with `s3://` remains queued; the worker will not pick it up.

Supported operation types for submission: `METADATA`, `THUMBNAIL`, `AUDIO_EXTRACTION`, `TRANSCODE_1080P`, `TRANSCODE_4K_TO_1080P`, `H264_TO_AV1`.

**Only `METADATA` is executed in Phase 2B.** Other types remain `QUEUED`. A job is not `COMPLETED` while those remain.

## METADATA worker (Phase 2B)

Requirements: Java 21, Docker (PostgreSQL), Go, FFmpeg/ffprobe.

Generate a tiny local clip (do not commit large binaries):

```bash
ffmpeg -y -f lavfi -i testsrc=duration=2:size=320x240:rate=30 -pix_fmt yuv420p /tmp/sample.mp4
```

Start PostgreSQL and the control service as above, then submit:

```bash
curl -sS -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "inputUri": "file:///tmp/sample.mp4",
    "operations": [{"type": "METADATA"}]
  }'
```

The job is `QUEUED` until the worker claims it.

Start the worker from the repository root:

```bash
cd worker
CONTROL_SERVICE_URL=http://localhost:8080 POLL_INTERVAL=1s go run ./cmd/worker
```

`FFPROBE_PATH` defaults to `ffprobe`. Stop the worker with SIGINT/SIGTERM: it stops polling and finishes or reports the in-flight operation.

Then:

```bash
curl -sS http://localhost:8080/jobs/<job-id>
```

Successful execution: operation and job become `COMPLETED`, with parsed metadata (`durationSeconds`, `formatName`, `videoCodec`, `width`, `height`, …) and `actualRuntimeMs`.

A missing file (`file:///does/not/exist.mp4`) becomes operation `FAILED` and job `FAILED`, with a persisted `failureReason`.

Internal worker endpoints (`POST /internal/operations/claim`, `.../complete`, `.../fail`) are for **local/trusted development only**. There is no authentication yet.

## What is inactive

The original Automated Video Processor code remains in the repository for history, but it is **not part of the active Phase 1 application**. That includes:

- Accounts / VideoAccounts
- Google Slides templates
- In-process JavaCV video composition
- AWS S3 helpers
- RabbitMQ prototype
- Google Drive and YouTube upload helpers
- GCP OAuth helpers
- Go/Kafka notification experiment
- DynamoDB bootstrap classes previously compiled into `Server/drive`

See [docs/legacy-system.md](docs/legacy-system.md) for the per-subsystem classification (KEEP / KEEP + MODIFY / REFACTOR / REPLACE / RETIRE).

## Development workflow

New work should happen on a feature or phase branch, not on `main`.

```bash
git checkout main
git pull

git checkout -b phase-2a-job-api

# make changes

git add .
git commit -m "Add durable job API"

git push -u origin phase-2a-job-api
```

Names such as `phase-2a-job-api`, `feature/job-persistence`, or `fix/job-validation` are examples only.

Then:

1. Open a pull request into `main`.
2. Wait for required CI checks.
3. Review the changes.
4. Merge only when CI is green.

Do not routinely push feature work directly to `main`.

Pushes to non-`main` branches run **Branch CI**. Pull requests to `main` and pushes/merges to `main` run **PR / Main CI**. Java CI executes `./mvnw clean test` from `Server/drive`. A second job, **Go tests**, runs `go vet` and `go test` in `worker/`. The existing required-check name **Java tests** is unchanged. After **Go tests** has run once on a pull request, add it as a required check as well.

These workflows are a **build/test gate**. They do not deploy anything. Deployment will be designed later.

See [docs/github-workflow.md](docs/github-workflow.md) for the full flow, the local CI equivalent, and the recommended GitHub settings to protect `main` (requiring a PR, requiring **Java tests**, blocking force pushes). Creating the YAML files does not enable those settings by itself.

## What comes later

Distributed execution, scheduling policies, worker registration, RabbitMQ dispatch, additional FFmpeg operations, object storage, and observability belong to later phases. Do not assume those features exist because the long-term design mentions them.
