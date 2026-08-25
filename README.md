# Adaptive Distributed Media Processing Platform

This repository is evolving from the original **Automated Video Processor** into a new portfolio project:

**Adaptive Distributed Media Processing Platform** — a distributed system that will eventually schedule heterogeneous media-processing jobs across workers based on workload characteristics, worker resources, load, priority, and deadlines.

That later architecture (Go scheduler policies, worker registration, OpenTelemetry) is **not implemented yet**. This repository is currently at **Phase 3A**.

## Current status: Phase 3A — RabbitMQ dispatch + multiple identical workers

The canonical Java application is the Maven/Spring Boot project at:

```text
Server/drive
```

Identical Go workers live at:

```text
worker/
```

The assignment JSON contract lives at:

```text
contracts/operation-assignment.v1.schema.json
```

Phase 3A currently:

- accepts job submissions and persists `Job` + `Operation` records in PostgreSQL (`POST /jobs` stays a fast DB write)
- a **Java dispatcher** (isolated from the public API) selects eligible `QUEUED` `METADATA`/`THUMBNAIL` operations, marks them `ASSIGNED`, and outbox-publishes assignment messages to RabbitMQ
- multiple Go workers compete as consumers on **one shared queue**
- workers call `POST /internal/operations/{id}/start` so PostgreSQL stays authoritative about whether work may execute
- downloads `s3://` inputs (and still accepts `file://`)
- runs **real ffprobe** and **real FFmpeg**
- uploads JPEG thumbnails to `s3://media-output/...` and persists `Artifact` metadata

RabbitMQ competing consumers are **baseline work distribution**, not the adaptive scheduler. Workers are treated as equivalent. There are no capabilities, heartbeats, leases, or worker-failure reassignment yet.

```text
Client
  |
  v
Java Control Service
  |
  v
PostgreSQL  <--- start / complete / fail
  ^
  |
  | outbox dispatcher
  v
RabbitMQ
  |
  +-------------+-------------+
  |             |             |
  v             v             v
Worker A     Worker B     Worker C
  |             |             |
  +-------------+-------------+
                |
         ffprobe / FFmpeg
                |
              MinIO
```

Stack: **Java 21**, **Spring Boot 4.1.1**, **Maven**, **PostgreSQL**, **Flyway**, **Spring Data JPA**, **Spring AMQP**, **Go**, **amqp091-go**, **ffprobe/FFmpeg**, **MinIO**, **RabbitMQ**. The Maven `artifactId` remains `drive`.

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

Java tests start a temporary PostgreSQL container. Dispatcher tests also start RabbitMQ via Testcontainers. They do **not** require the Compose database, MinIO, or Compose RabbitMQ. Some Go tests generate a tiny clip with FFmpeg when `ffmpeg`/`ffprobe` are on `PATH`; they are skipped if those binaries are missing. GitHub Actions does **not** install FFmpeg or MinIO. Object-storage unit tests use an in-memory fake. Go broker tests start RabbitMQ via Testcontainers.

## Local infrastructure

From the repository root:

```bash
docker compose up -d postgres minio minio-init rabbitmq
```

This starts:

- PostgreSQL 16 on port **5432** (database/user/password `media_platform`)
- MinIO S3 API on port **9000** and console on **9001**
- a one-shot `minio-init` container that creates buckets `media-input` and `media-output`
- RabbitMQ 3.13 on port **5672** (AMQP) and management UI on **15672**

Those database, MinIO, and RabbitMQ values are **local development defaults**, not production secrets. MinIO console: [http://localhost:9001](http://localhost:9001) (`minioadmin` / `minioadmin`). RabbitMQ management: [http://localhost:15672](http://localhost:15672) (`media_platform` / `media_platform`).

If host ports are already in use:

```bash
POSTGRES_PORT=55432 MINIO_API_PORT=19000 MINIO_CONSOLE_PORT=19001 \
  RABBITMQ_AMQP_PORT=5673 RABBITMQ_MANAGEMENT_PORT=15673 \
  docker compose up -d postgres minio minio-init rabbitmq
DB_URL=jdbc:postgresql://localhost:55432/media_platform
OBJECT_STORE_ENDPOINT=http://localhost:19000
RABBITMQ_PORT=5673
```

Override Postgres connection settings with:

```text
DB_URL          default jdbc:postgresql://localhost:5432/media_platform
DB_USERNAME     default media_platform
DB_PASSWORD     default media_platform
```

## Start the application

```bash
docker compose up -d postgres minio minio-init rabbitmq
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

Submit a job. Execution is not started inside this request; the job is stored as `QUEUED`. An isolated dispatcher later assigns eligible operations.

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
curl -sS http://localhost:8080/jobs/<job-id>/artifacts
```

`priority` defaults to `NORMAL` when omitted. `deadline` is optional. Unknown jobs return **404**. Invalid bodies (missing `inputUri`, empty `operations`, unknown operation type, past deadline) return **400**.

`inputUri` is stored as a URI string. The public API does **not** contact S3 or verify that the object exists. **Phase 3A dispatches and executes `file://` and `s3://` for `METADATA` and `THUMBNAIL` only.** Other submitted types remain `QUEUED`. A job is not `COMPLETED` while those remain.

Supported operation types for submission: `METADATA`, `THUMBNAIL`, `AUDIO_EXTRACTION`, `TRANSCODE_1080P`, `TRANSCODE_4K_TO_1080P`, `H264_TO_AV1`.

**Only `METADATA` and `THUMBNAIL` are executed in Phase 3A.**

## Workers (Phase 3A)

Requirements: Java 21, Docker (PostgreSQL + MinIO + RabbitMQ), Go, FFmpeg/ffprobe.

Generate a tiny local clip (do not commit large binaries):

```bash
ffmpeg -y -f lavfi -i testsrc=duration=2:size=320x240:rate=30 -pix_fmt yuv420p /tmp/sample.mp4
```

Upload it to MinIO. With the AWS CLI:

```bash
AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin \
  aws --endpoint-url http://localhost:9000 s3 cp /tmp/sample.mp4 s3://media-input/sample.mp4
```

Or with the MinIO client in Docker:

```bash
docker run --rm --network host -v /tmp/sample.mp4:/sample.mp4 minio/mc \
  sh -c 'mc alias set local http://localhost:9000 minioadmin minioadmin && mc cp /sample.mp4 local/media-input/sample.mp4'
```

Canonical object: `s3://media-input/sample.mp4`.

Start PostgreSQL, MinIO, RabbitMQ, and the control service as above, then submit:

```bash
curl -sS -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "inputUri": "s3://media-input/sample.mp4",
    "operations": [
      {"type": "METADATA"},
      {"type": "THUMBNAIL"}
    ]
  }'
```

The job is `QUEUED` until the dispatcher assigns operations (`ASSIGNED`), a worker starts one (`RUNNING`), and results are persisted (`COMPLETED` / `FAILED`).

Start two or more identical workers:

```bash
cd worker

WORKER_ID=worker-a \
CONTROL_SERVICE_URL=http://localhost:8080 \
RABBITMQ_URL=amqp://media_platform:media_platform@localhost:5672/ \
PREFETCH=1 \
OBJECT_STORE_ENDPOINT=http://localhost:9000 \
OBJECT_STORE_REGION=us-east-1 \
OBJECT_STORE_ACCESS_KEY=minioadmin \
OBJECT_STORE_SECRET_KEY=minioadmin \
OBJECT_STORE_FORCE_PATH_STYLE=true \
OUTPUT_BUCKET=media-output \
go run ./cmd/worker

WORKER_ID=worker-b \
CONTROL_SERVICE_URL=http://localhost:8080 \
RABBITMQ_URL=amqp://media_platform:media_platform@localhost:5672/ \
PREFETCH=1 \
OBJECT_STORE_ENDPOINT=http://localhost:9000 \
OBJECT_STORE_REGION=us-east-1 \
OBJECT_STORE_ACCESS_KEY=minioadmin \
OBJECT_STORE_SECRET_KEY=minioadmin \
OBJECT_STORE_FORCE_PATH_STYLE=true \
OUTPUT_BUCKET=media-output \
go run ./cmd/worker
```

Those MinIO and RabbitMQ keys are local development defaults. `WORKER_ID` is for logs only; it is not a registry. `PREFETCH` defaults to `1`. `FFPROBE_PATH` defaults to `ffprobe`. `FFMPEG_PATH` defaults to `ffmpeg`. Stop a worker with SIGINT/SIGTERM: in-flight unacked messages are requeued by RabbitMQ.

Then:

```bash
curl -sS http://localhost:8080/jobs/<job-id>
curl -sS http://localhost:8080/jobs/<job-id>/artifacts
```

Successful execution:

```text
QUEUED -> ASSIGNED -> RUNNING -> COMPLETED
```

Observe worker logs for `worker=`, `job=`, `operation=`, `type=`, `event=received`, `event=execution_start`, `event=execution_completed` / `event=execution_failure`, and `event=ack` / `event=nack_requeue`.

`METADATA` stores parsed probe JSON on the operation. `THUMBNAIL` extracts one JPEG frame (seek ~1s, falling back to the first frame on short clips) and uploads:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/thumbnail.jpg
```

`GET /jobs/{id}/artifacts` returns type, object URI, content type, size, and SHA-256 checksum. Image bytes stay in MinIO.

`file://` inputs still work for both operations. Thumbnail output is always stored in the output bucket.

A missing object (`s3://media-input/does-not-exist.mp4`) becomes operation `FAILED` and job `FAILED`, with a persisted `failureReason` that does not include credentials.

Duplicate RabbitMQ delivery cannot rerun completed (or already running) work: `POST /internal/operations/{id}/start` is a conditional `ASSIGNED -> RUNNING` transition. `ALREADY_RUNNING` / `ALREADY_TERMINAL` is acknowledged without executing media again.

Internal worker endpoints (`POST /internal/operations/{id}/start`, `.../complete`, `.../fail`) are for **local/trusted development only**. There is no authentication yet.

`POST /internal/operations/claim` still exists but is **disabled by default** (`drive.dispatch.http-claim-enabled=false`) so it does not compete with RabbitMQ. Existing tests turn it on. Do not run poll-based workers against a dispatcher-enabled control service.

### Phase 3A limitations

- workers are treated as equivalent
- no worker registration or capabilities
- no heartbeats or worker-failure reassignment
- no leases / attempt IDs
- no FIFO / round-robin / least-loaded / SJF / EDF / adaptive scheduler
- RabbitMQ competing consumers are not that scheduler

## What is inactive

The original Automated Video Processor code remains in the repository for history, but it is **not part of the active Phase 1 application**. That includes:

- Accounts / VideoAccounts
- Google Slides templates
- In-process JavaCV video composition
- AWS S3 helpers
- the original RabbitMQ prototype under `Server/RabbitMQ`
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

Distributed scheduling policies, worker registration and capabilities, heartbeats, leases, additional FFmpeg operations, and observability belong to later phases. RabbitMQ is only the delivery mechanism today.
