# Adaptive Distributed Media Processing Platform

This repository is evolving from the original **Automated Video Processor** into a new portfolio project:

**Adaptive Distributed Media Processing Platform** — a distributed system that will eventually schedule heterogeneous media-processing jobs across workers based on workload characteristics, worker resources, load, priority, and deadlines.

That later architecture (Go scheduler policies, OpenTelemetry) is **not implemented yet**. This repository is currently at **Phase 3D**.

## Current status: Phase 3D — execution attempts + leases + safe reassignment

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

Phase 3D currently:

- accepts job submissions and persists `Job` + `Operation` records in PostgreSQL (`POST /jobs` stays a fast DB write)
- a **Java dispatcher** (isolated from the public API) selects eligible `QUEUED` `METADATA`/`THUMBNAIL` operations, marks them `ASSIGNED`, and outbox-publishes assignment messages to RabbitMQ
- Go workers **probe local capabilities and register** with the control service before consuming work
- workers send **periodic heartbeats**; the control service marks them `AVAILABLE` or `UNAVAILABLE`
- `GET /workers` lists workers, static capabilities, `status`, and `lastHeartbeat`
- multiple Go workers compete as consumers on **one shared queue** (availability is not placement yet)
- workers call `POST /internal/operations/{id}/start` with `workerId` so PostgreSQL creates an `ExecutionAttempt`, binds ownership, and issues a lease
- workers renew that lease independently of heartbeats while media work runs
- if a worker becomes `UNAVAILABLE` and its attempt lease expires, the attempt is `INTERRUPTED`, the operation is `QUEUED`, and the existing dispatcher redispatches it
- a late result from an old attempt is rejected (`409 STALE_EXECUTION_ATTEMPT`) and cannot complete a newer attempt's operation
- downloads `s3://` inputs (and still accepts `file://`)
- runs **real ffprobe** and **real FFmpeg**
- uploads JPEG thumbnails to `s3://media-output/...` and persists `Artifact` metadata

RabbitMQ competing consumers are **baseline work distribution**, not the adaptive scheduler. Registration is control-plane inventory. Heartbeats are control-plane liveness. Leases are **attempt ownership**. Neither heartbeat nor lease is worker placement.

```text
Client
  |
  v
Java Control Service
  |              \
  |               +--> GET /workers  (AVAILABLE / UNAVAILABLE)
  v
PostgreSQL  <--- worker registration (upsert by WORKER_ID)
  ^              <--- POST /internal/workers/{id}/heartbeat
  ^              <--- start (creates ExecutionAttempt + lease)
  ^              <--- renew / complete / fail (attemptId required)
  |
  | stale-heartbeat sweeper
  |   AVAILABLE -> UNAVAILABLE when lastHeartbeat is older than timeout
  |
  | expired-lease sweeper
  |   RUNNING attempt + expired lease + UNAVAILABLE worker
  |     -> attempt INTERRUPTED, operation QUEUED
  |
  | outbox dispatcher (still shared-queue publish; redispatches requeued work)
  v
RabbitMQ
  |
  +-------------+-------------+
  |             |             |
  v             v             v
Worker A     Worker B     Worker C
  |             |             |
  +------ register + heartbeat loop ------+
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
curl -sS http://localhost:8080/jobs/<job-id>/operations/<operation-id>/attempts
curl -sS http://localhost:8080/jobs/<job-id>/artifacts
```

`priority` defaults to `NORMAL` when omitted. `deadline` is optional. Unknown jobs return **404**. Invalid bodies (missing `inputUri`, empty `operations`, unknown operation type, past deadline) return **400**.

`inputUri` is stored as a URI string. The public API does **not** contact S3 or verify that the object exists. Dispatch still executes `file://` and `s3://` for `METADATA` and `THUMBNAIL` only. Other submitted types remain `QUEUED`. A job is not `COMPLETED` while those remain.

Supported operation types for submission: `METADATA`, `THUMBNAIL`, `AUDIO_EXTRACTION`, `TRANSCODE_1080P`, `TRANSCODE_4K_TO_1080P`, `H264_TO_AV1`.

**Only `METADATA` and `THUMBNAIL` are executed.** Dispatch still publishes those types to the shared RabbitMQ queue; registration does not change placement.

## Workers

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

The job is `QUEUED` until the dispatcher assigns operations (`ASSIGNED`), a worker starts one (`RUNNING` + `ExecutionAttempt`), and results are persisted (`COMPLETED` / `FAILED`). Interrupted infrastructure failures requeue the operation; attempt history is retained.

`WORKER_ID` is **required** (stable identity such as `worker-a`). The worker probes local executables and machine info, registers, starts a heartbeat loop, and only then consumes RabbitMQ. After `start` succeeds it also runs a **lease-renewal loop** for that attempt until complete/fail. `supportedOperations` means the worker has an implemented executor **and** the required local binary is available (`METADATA` needs ffprobe, `THUMBNAIL` needs FFmpeg). Optional `SUPPORTED_OPERATIONS` may **restrict** that set; it cannot add unimplemented types. If a requested operation's executable is missing, startup fails: the worker does not register, does not heartbeat, and does not consume. Metadata-only workers (`SUPPORTED_OPERATIONS=METADATA`) do not require FFmpeg; encoder `supportedCodecs` stay empty in that case.

```bash
cd worker

WORKER_ID=worker-a \
HEARTBEAT_INTERVAL=5s \
LEASE_RENEW_INTERVAL=10s \
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
HEARTBEAT_INTERVAL=5s \
LEASE_RENEW_INTERVAL=10s \
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

Heterogeneous registry demo (still the same binary; restriction only):

```bash
SUPPORTED_OPERATIONS=METADATA WORKER_ID=worker-b ... go run ./cmd/worker
```

Those MinIO and RabbitMQ keys are local development defaults. `PREFETCH` defaults to `1`. `FFPROBE_PATH` defaults to `ffprobe`. `FFMPEG_PATH` defaults to `ffmpeg`. `WORKER_HOSTNAME` overrides `os.Hostname()` when set. `HEARTBEAT_INTERVAL` defaults to `5s` (Go duration, for example `5s` or `500ms`). `LEASE_RENEW_INTERVAL` defaults to `10s` and should stay below half of `OPERATION_LEASE_DURATION` (control-service default `30s`). Invalid or non-positive values fail startup. Stop a worker with SIGINT/SIGTERM: in-flight unacked messages are requeued by RabbitMQ; missed heartbeats eventually mark the worker `UNAVAILABLE`; an unrenewed lease plus `UNAVAILABLE` lets the control plane interrupt the attempt and requeue the operation. There is no explicit relinquish endpoint in this phase.

Then:

```bash
curl -sS http://localhost:8080/workers
curl -sS http://localhost:8080/jobs/<job-id>
curl -sS http://localhost:8080/jobs/<job-id>/artifacts
```

Successful execution:

```text
QUEUED -> ASSIGNED -> RUNNING -> COMPLETED
```

Observe worker logs for `event=registered`, then `worker=`, `job=`, `operation=`, `type=`, `event=received`, `event=execution_start`, `event=execution_completed` / `event=execution_failure`, and `event=ack` / `event=nack_requeue`. An assignment this worker did not advertise (for example `TRANSCODE_1080P`) is not executed; the message is dead-lettered (`event=capability_mismatch`).

`METADATA` stores parsed probe JSON on the operation. `THUMBNAIL` extracts one JPEG frame (seek ~1s, falling back to the first frame on short clips) and uploads:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/thumbnail.jpg
```

`GET /jobs/{id}/artifacts` returns type, object URI, content type, size, and SHA-256 checksum. Image bytes stay in MinIO.

## Worker API

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

Those MinIO and RabbitMQ keys are local development defaults. `WORKER_ID` is the durable registry identity (see Worker API above). `PREFETCH` defaults to `1`. `FFPROBE_PATH` defaults to `ffprobe`. `FFMPEG_PATH` defaults to `ffmpeg`. Stop a worker with SIGINT/SIGTERM: in-flight unacked messages are requeued by RabbitMQ.

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

## Worker API

Workers register at startup. Registration is an upsert keyed by `WORKER_ID`. First registration returns **201**; a later registration of the same ID returns **200**, updates capabilities/resources, **preserves `registeredAt`**, and refreshes liveness (`status = AVAILABLE`, `lastHeartbeat = now`). Registration initializes liveness; heartbeats maintain it.

```text
worker starts
    ↓
detects capabilities (executables + static resources)
    ↓
POST /internal/workers/register
    ↓
AVAILABLE
    ↓
start heartbeat loop (POST /internal/workers/{id}/heartbeat)
    ↓
RabbitMQ consume

periodic heartbeat
    ↓
AVAILABLE

heartbeat stops
    ↓
timeout + sweeper
    ↓
UNAVAILABLE

heartbeat or re-register
    ↓
AVAILABLE
```

If registration fails after bounded retries, the worker exits and does **not** heartbeat or consume. Transient heartbeat failures are logged and retried on the next interval; they do not kill the worker. The heartbeat loop is independent of media execution and of the RabbitMQ consume loop.

Control-service liveness config (Spring durations, default development values):

```text
WORKER_HEARTBEAT_TIMEOUT=15s
WORKER_HEARTBEAT_SWEEP_INTERVAL=5s
```

Timeout should be greater than `HEARTBEAT_INTERVAL`. A worker with `lastHeartbeat = null` is not considered live (migrated inventory rows start as `UNAVAILABLE`).

```bash
curl -sS http://localhost:8080/workers
curl -sS http://localhost:8080/workers/worker-a
```

Example list:

```json
{
  "workers": [
    {
      "id": "worker-a",
      "status": "AVAILABLE",
      "hostname": "mac-worker-a",
      "supportedOperations": ["METADATA", "THUMBNAIL"],
      "supportedCodecs": ["h264", "hevc"],
      "cpuArchitecture": "arm64",
      "cpuCores": 8,
      "memoryBytes": 17179869184,
      "ffmpegVersion": "8.1.2",
      "lastHeartbeat": "2026-08-25T22:00:05Z",
      "registeredAt": "2026-08-25T22:00:00Z",
      "updatedAt": "2026-08-25T22:00:05Z"
    }
  ]
}
```

`AVAILABLE` means the control service has observed a registration or heartbeat within `WORKER_HEARTBEAT_TIMEOUT`. `UNAVAILABLE` means it has not. That is **not** proof the OS process is dead, and it does **not** stop RabbitMQ from delivering to a still-connected consumer. Unknown IDs return **404** `WORKER_NOT_FOUND`. Heartbeat of an unknown worker also returns **404**; heartbeats never auto-register. An `UNAVAILABLE` worker can become `AVAILABLE` again via heartbeat or re-registration.

Parent Job status after start/complete/fail is recomputed under a PostgreSQL row lock on the Job, so concurrent operation completions cannot leave the job stale (for example both operations `COMPLETED` while the job stays `RUNNING`). That is aggregation correctness, not exactly-once execution.

Internal `POST /internal/workers/register` and `POST /internal/workers/{workerId}/heartbeat` are for trusted workers only (no auth yet).

`file://` inputs still work for both operations. Thumbnail output is always stored in the output bucket.

A missing object (`s3://media-input/does-not-exist.mp4`) becomes operation `FAILED` and job `FAILED`, with a persisted `failureReason` that does not include credentials.

Internal worker endpoints (`POST /internal/operations/{id}/start`, `.../attempts/{attemptId}/renew`, `.../complete`, `.../fail`) are for **local/trusted development only**. There is no authentication yet. Credential for ownership is the unguessable attempt UUID plus `workerId` on the trusted network; there is no extra lease token.

`POST /internal/operations/claim` still exists but is **disabled by default** (`drive.dispatch.http-claim-enabled=false`) so it does not compete with RabbitMQ. Existing tests turn it on. Claim now also requires `workerId` and creates an attempt. Do not run poll-based workers against a dispatcher-enabled control service.

## Execution ownership

`Operation` is the user-facing logical task. `ExecutionAttempt` is one concrete worker execution of that operation.

```text
Operation THUMBNAIL
    |
    +-- Attempt 1 worker-a INTERRUPTED
    |
    +-- Attempt 2 worker-b COMPLETED   → Operation COMPLETED
```

RabbitMQ still selects the consumer. The assignment JSON does **not** bind a worker or attempt. Ownership is established only at start:

```text
dispatcher: Operation QUEUED -> ASSIGNED, publish v1 assignment
worker receives message
    ↓
POST /internal/operations/{id}/start  {"workerId":"worker-a"}
    ↓
control service (Job row lock):
    worker exists, AVAILABLE, advertises the type
    Operation ASSIGNED
    no RUNNING attempt already
    create ExecutionAttempt RUNNING, attemptNumber, leaseExpiresAt
    Operation RUNNING
    return attemptId + leaseExpiresAt
```

A second start while the operation is already `RUNNING` returns `ALREADY_RUNNING` and does not create another attempt.

### Lease vs heartbeat

```text
heartbeat  = worker process / control-plane liveness   (AVAILABLE / UNAVAILABLE)
lease      = ownership of one execution attempt        (leaseExpiresAt)
```

A worker may be `AVAILABLE` with no active leases. A `RUNNING` attempt always has a lease. Lease expiration is **not** proof the worker is dead.

Control-service lease config:

```text
OPERATION_LEASE_DURATION=30s
OPERATION_LEASE_SWEEP_INTERVAL=5s
MAX_EXECUTION_ATTEMPTS=3
```

Worker:

```text
LEASE_RENEW_INTERVAL=10s
```

Keep `LEASE_RENEW_INTERVAL` less than half of `OPERATION_LEASE_DURATION`. Transient renew failures are logged and retried on the next interval; they do not kill media work. If renewals stop and the worker is later `UNAVAILABLE`, the control plane may reclaim the attempt. A late `complete`/`fail` from that attempt is then `409 STALE_EXECUTION_ATTEMPT`.

### Recovery after worker failure

```text
worker-a starts Attempt 1
    ↓
worker-a disappears; heartbeats stop
    ↓
worker-a -> UNAVAILABLE
    ↓
Attempt 1 lease expires
    ↓
sweeper: lease expired AND worker UNAVAILABLE
    ↓
Attempt 1 INTERRUPTED, Operation QUEUED
    ↓
existing dispatcher redispatches (outbox row for that operation is cleared so a new publish can occur)
    ↓
worker-b start -> Attempt 2
```

The operation is requeued rather than left in a lasting `INTERRUPTED` status. The job stays `RUNNING` if other operations are still unfinished; a single requeued operation can make the job `QUEUED` again until the dispatcher assigns it.

An expired lease on an `AVAILABLE` worker is **not** reclaimed. That avoids stealing work after one missed renew.

After `MAX_EXECUTION_ATTEMPTS` infrastructure interruptions, the operation becomes `FAILED` with reason `maximum execution attempts exceeded`. Real FFmpeg/ffprobe errors still fail the attempt immediately and are **not** auto-retried.

### Stale-result safety

Complete and fail require `attemptId`. If that attempt is not the current `RUNNING` owner:

- HTTP `409` `STALE_EXECUTION_ATTEMPT`
- Operation / Job status unchanged
- no Artifact row written or overwritten

Attempt 1 cannot complete Attempt 2's work.

### Artifact retries

Thumbnail object keys stay `s3://media-output/jobs/<jobId>/operations/<operationId>/thumbnail.jpg`. A retry may overwrite the same key. If a worker uploads then dies before `complete` persists, the object can exist without an Artifact row. That orphan is **not** garbage-collected in this phase. Execution is **at-least-once**, not exactly-once.

`GET /jobs/{jobId}/operations/{operationId}/attempts` is a read-only history API (no lease internals).

### Phase 3D limitations

- no FIFO / round-robin / least-loaded / SJF / EDF / adaptive scheduler
- no capability-aware placement; the shared RabbitMQ queue still distributes work
- workers are not assigned explicitly before delivery
- no CPU/memory utilization telemetry
- no OpenTelemetry / Prometheus / Grafana / Jaeger
- no full retry/backoff policy beyond bounded interruption requeue
- no benchmark framework
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

The smallest next milestone is **baseline scheduling policies** (FIFO / round-robin / least-loaded) on top of this ownership model. That is Phase 4. Additional FFmpeg operations, runtime estimation, utilization telemetry, and OpenTelemetry remain later still. RabbitMQ is only the delivery mechanism today. Heartbeats remain process liveness; leases remain attempt ownership.
