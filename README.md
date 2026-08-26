# Adaptive Distributed Media Processing Platform

This repository is evolving from the original **Automated Video Processor** into a new portfolio project:

**Adaptive Distributed Media Processing Platform** — a distributed system that will eventually schedule heterogeneous media-processing jobs across workers based on workload characteristics, worker resources, load, priority, and deadlines.

This repository is currently at **Phase 4A**: a Go scheduler makes explicit FIFO placement decisions. RabbitMQ only transports those decisions to the selected worker. Later policies (Round Robin, Least Loaded, SJF, EDF, Adaptive) are not implemented.

## Current status: Phase 4A — FIFO scheduler + explicit worker placement

The canonical Java application is the Maven/Spring Boot project at:

```text
Server/drive
```

The Go scheduler lives at:

```text
scheduler/
```

Identical Go workers live at:

```text
worker/
```

Assignment JSON contracts live at:

```text
contracts/operation-assignment.v1.schema.json   (legacy shared-queue path)
contracts/operation-assignment.v2.schema.json   (Phase 4A targeted placement)
```

Phase 4A currently:

- accepts job submissions and persists `Job` + `Operation` records in PostgreSQL (`POST /jobs` stays a fast DB write and does **not** publish RabbitMQ)
- a **Go scheduler** polls `GET /internal/scheduler/snapshot`, selects the oldest eligible operation (FIFO) and an explicit worker, then commits with `POST /internal/scheduler/assign`
- Java revalidates the decision in one transaction: operation still `QUEUED`, worker `AVAILABLE`, worker advertises the type, then `QUEUED -> ASSIGNED`, writes a `scheduling_decisions` row (`policy=FIFO`), and creates a **worker-targeted** outbox row
- the Java outbox publisher sends that assignment to RabbitMQ with routing key `worker.{workerId}`
- Go workers declare durable per-worker queues before they register, consume only their queue, and reject a v2 assignment whose `workerId` does not match
- workers send **periodic heartbeats**; the control service marks them `AVAILABLE` or `UNAVAILABLE`
- `GET /workers` lists workers, static capabilities, `status`, and `lastHeartbeat`
- workers call `POST /internal/operations/{id}/start` with `workerId` so PostgreSQL creates an `ExecutionAttempt`, binds ownership, and issues a lease
- workers renew that lease independently of heartbeats while media work runs
- if a worker becomes `UNAVAILABLE` and its attempt lease expires, the attempt is `INTERRUPTED`, the operation is `QUEUED`, and the Go scheduler can place it on another eligible worker
- a late result from an old attempt is rejected (`409 STALE_EXECUTION_ATTEMPT`)
- downloads `s3://` inputs (and still accepts `file://`)
- runs **real ffprobe** and **real FFmpeg**
- uploads JPEG thumbnails to `s3://media-output/...` and persists `Artifact` metadata

FIFO is a **control baseline**, not a performance claim. It does not use job priority, deadline, CPU, memory, queue depth, or runtime estimates.

Worker placement in this phase is a separate, deliberately simple rule: among `AVAILABLE` workers that advertise the operation type, choose the lexicographically first worker ID. **This is not Round Robin or adaptive placement.**

```text
Client
  |
  v
Java Control Service
  |              \
  |               +--> GET /workers  (AVAILABLE / UNAVAILABLE)
  |               +--> GET /internal/scheduler/snapshot
  |               +--> POST /internal/scheduler/assign
  v
PostgreSQL  <--- worker registration (upsert by WORKER_ID)
  ^              <--- POST /internal/workers/{id}/heartbeat
  ^              <--- start (creates ExecutionAttempt + lease)
  ^              <--- renew / complete / fail (attemptId required)
  ^              <--- scheduling_decisions + targeted dispatch_outbox
  |
  | stale-heartbeat sweeper
  |   AVAILABLE -> UNAVAILABLE when lastHeartbeat is older than timeout
  |
  | expired-lease sweeper
  |   RUNNING attempt + expired lease + UNAVAILABLE worker
  |     -> attempt INTERRUPTED, operation QUEUED
  |
  | outbox publisher (routing key worker.{workerId})
  v
RabbitMQ  media.operations
  |
  +-- worker.worker-a --> media.worker.worker-a --> Worker A
  +-- worker.worker-b --> media.worker.worker-b --> Worker B
                |
         ffprobe / FFmpeg
                |
              MinIO

Go Scheduler (placement authority)
  |
  +--> snapshot --> FIFO operation --> lex-first eligible worker --> assign
```

Stack: **Java 21**, **Spring Boot 4.1.1**, **Maven**, **PostgreSQL**, **Flyway**, **Spring Data JPA**, **Spring AMQP**, **Go**, **amqp091-go**, **ffprobe/FFmpeg**, **MinIO**, **RabbitMQ**. The Maven `artifactId` remains `drive`.

## Build

From `Server/drive`:

```bash
./mvnw clean test
```

Requires **Java 21+**. Automated Java tests use **Testcontainers** and therefore need a running **Docker daemon**. The Maven wrapper (`./mvnw`) is preferred over a system Maven install.

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

```bash
cd scheduler
go test ./...
go vet ./...
```

Java tests start a temporary PostgreSQL container. Dispatcher and scheduler RabbitMQ tests also start RabbitMQ via Testcontainers. They do **not** require the Compose database, MinIO, or Compose RabbitMQ. Some Go tests generate a tiny clip with FFmpeg when `ffmpeg`/`ffprobe` are on `PATH`; they are skipped if those binaries are missing. GitHub Actions does **not** install FFmpeg or MinIO. Object-storage unit tests use an in-memory fake. Worker broker tests start RabbitMQ via Testcontainers. Scheduler tests are unit tests (no Docker).

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

The service listens on port **8080** by default. Override with `SERVER_PORT`. Java operation-selection (`drive.dispatch.scheduling-enabled`) is **off** so it does not compete with the Go scheduler. The outbox publisher stays on.

In another terminal, start the scheduler:

```bash
cd scheduler
CONTROL_SERVICE_URL=http://localhost:8080 \
SCHEDULER_POLL_INTERVAL=500ms \
SCHEDULING_POLICY=FIFO \
go run ./cmd/scheduler
```

`SCHEDULING_POLICY` must be `FIFO`. Other names (including `ROUND_ROBIN`) fail startup; those policies are not implemented. Then start workers (see below).

Override the control-service port with `SERVER_PORT`:

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

Submit a job. Execution is not started inside this request; the job is stored as `QUEUED`. The Go scheduler later assigns eligible operations.

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

**Only `METADATA` and `THUMBNAIL` are executed.** The scheduler places those types onto a specific worker; registration and capability matching now prevent dispatch to a worker that did not advertise the type. Other submitted types remain `QUEUED`.

## Scheduler

The scheduler decides **placement**. RabbitMQ **transports** that placement. The worker **executes** it.

```text
queued operation
      ↓
Go scheduler (FIFO + lex-first eligible worker)
      ↓
POST /internal/scheduler/assign
      ↓
Java transaction: validate, QUEUED -> ASSIGNED, scheduling_decisions, targeted outbox
      ↓
publisher -> routing key worker.{workerId}
      ↓
only that worker's queue receives the message
      ↓
POST /internal/operations/{id}/start  (attempt + lease; unchanged from Phase 3D)
```

### FIFO (operation ordering)

Scheduling unit is **Operation**, not whole Job. FIFO means the oldest **schedulable operation across jobs**:

```text
createdAt ASC, operationOrder ASC, id ASC
```

Job `priority` and `deadline` are persisted but **intentionally ignored** so FIFO stays a pure baseline. This phase does not skip an older unschedulable operation to run a younger one; if the oldest queued `THUMBNAIL` has no eligible worker, it stays `QUEUED` and the scheduler logs `no_eligible_worker` (no hot loop — it sleeps `SCHEDULER_POLL_INTERVAL`). Worker registration/recovery may make it schedulable later. The operation is not failed.

### Worker placement (not a performance algorithm)

After FIFO picks the operation:

```text
eligible workers
    ↓
status == AVAILABLE
    ↓
supports the operation type
    ↓
sort worker IDs lexicographically
    ↓
choose first
```

Example: `worker-a` and `worker-b` both `AVAILABLE` and both advertise `METADATA` → `worker-a` wins. If `worker-a` is `UNAVAILABLE`, `worker-b` wins. **This is not Round Robin, Least Loaded, or adaptive scoring.**

Java revalidates worker existence, `AVAILABLE`, and capability at assign commit. A stale snapshot is rejected (`409`), and the scheduler continues.

Two scheduler processes may propose the same operation; the Job row lock allows only one `QUEUED -> ASSIGNED`. The loser gets `409` and retries the next loop.

### Targeted RabbitMQ

| Name | Value |
| --- | --- |
| Exchange | `media.operations` (direct, durable) |
| Worker queue | `media.worker.{workerId}` (durable, worker-declared) |
| Routing key | `worker.{workerId}` |
| Dead-letter exchange | `media.operations.dlx` |
| Dead-letter queue | `media.operations.execute.dlq` |

Workers declare their queue **before** registration so they are not marked `AVAILABLE` with a missing queue. Offline `UNAVAILABLE` workers are not assigned. Durable queues are **not** deleted when a worker becomes `UNAVAILABLE` (cleanup is later technical debt). Messages for a worker that dies after publish can sit on that durable queue until the worker returns; after `start`, Phase 3D lease recovery still applies.

A scheduler snapshot can be stale (worker dies between snapshot and commit). Commit revalidation plus leases provide eventual progress; this phase does not eliminate every race.

The legacy Java enqueue loop (`drive.dispatch.scheduling-enabled=true`) can still select `QUEUED` work onto the old shared queue for tests. Production default is **off**. Do not run it together with the Go scheduler.

### Assignment v2

```json
{
  "schemaVersion": 2,
  "operationId": "...",
  "jobId": "...",
  "type": "THUMBNAIL",
  "inputUri": "s3://...",
  "workerId": "worker-a",
  "scheduledAt": "...",
  "policy": "FIFO"
}
```

No `attemptId`. Ownership still begins at `start`. The worker drops a v2 message whose `workerId` does not match `WORKER_ID`.

## Workers

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

The job is `QUEUED` until the scheduler assigns an operation (`ASSIGNED`), a worker starts one (`RUNNING` + `ExecutionAttempt`), and results are persisted (`COMPLETED` / `FAILED`). Interrupted infrastructure failures requeue the operation; the scheduler places it again. Attempt history is retained.

`WORKER_ID` is **required** (stable identity such as `worker-a`). The worker probes local executables and machine info, **declares its durable RabbitMQ queue**, registers, starts a heartbeat loop, and only then consumes `media.worker.{WORKER_ID}`. After `start` succeeds it also runs a **lease-renewal loop** for that attempt until complete/fail. `supportedOperations` means the worker has an implemented executor **and** the required local binary is available (`METADATA` needs ffprobe, `THUMBNAIL` needs FFmpeg). Optional `SUPPORTED_OPERATIONS` may **restrict** that set; it cannot add unimplemented types. If a requested operation's executable is missing, startup fails: the worker does not register, does not heartbeat, and does not consume. Metadata-only workers (`SUPPORTED_OPERATIONS=METADATA`) do not require FFmpeg; encoder `supportedCodecs` stay empty in that case.

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

Observe worker logs for `event=registered`, then `event=consuming queue=media.worker.{id}`, `event=received`, `event=execution_start`, `event=execution_completed` / `event=execution_failure`, and `event=ack` / `event=nack_requeue`. A v2 assignment whose `workerId` does not match is dropped (`event=worker_id_mismatch`). An assignment this worker did not advertise (for example `TRANSCODE_1080P`) is not executed; the message is dead-lettered (`event=capability_mismatch`). Scheduler logs include `event=assigned` and `event=no_eligible_worker`.

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
declare durable queue media.worker.{WORKER_ID}
    ↓
POST /internal/workers/register
    ↓
AVAILABLE
    ↓
start heartbeat loop (POST /internal/workers/{id}/heartbeat)
    ↓
RabbitMQ consume media.worker.{WORKER_ID}

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

`AVAILABLE` means the control service has observed a registration or heartbeat within `WORKER_HEARTBEAT_TIMEOUT`. `UNAVAILABLE` means it has not. That is **not** proof the OS process is dead. The scheduler will not **newly assign** work to `UNAVAILABLE` workers. A still-connected consumer can still finish an already-started attempt until the lease expires. Unknown IDs return **404** `WORKER_NOT_FOUND`. Heartbeat of an unknown worker also returns **404**; heartbeats never auto-register. An `UNAVAILABLE` worker can become `AVAILABLE` again via heartbeat or re-registration.

Parent Job status after start/complete/fail is recomputed under a PostgreSQL row lock on the Job, so concurrent operation completions cannot leave the job stale (for example both operations `COMPLETED` while the job stays `RUNNING`). That is aggregation correctness, not exactly-once execution.

Internal `POST /internal/workers/register` and `POST /internal/workers/{workerId}/heartbeat` are for trusted workers only (no auth yet).

`file://` inputs still work for both operations. Thumbnail output is always stored in the output bucket.

A missing object (`s3://media-input/does-not-exist.mp4`) becomes operation `FAILED` and job `FAILED`, with a persisted `failureReason` that does not include credentials.

Internal worker endpoints (`POST /internal/operations/{id}/start`, `.../attempts/{attemptId}/renew`, `.../complete`, `.../fail`) are for **local/trusted development only**. There is no authentication yet. Credential for ownership is the unguessable attempt UUID plus `workerId` on the trusted network; there is no extra lease token.

`POST /internal/operations/claim` still exists but is **disabled by default** (`drive.dispatch.http-claim-enabled=false`) so it does not compete with the scheduler. Existing tests turn it on. Claim now also requires `workerId` and creates an attempt. Do not run poll-based workers against a scheduler-enabled control service.

## Execution ownership

`Operation` is the user-facing logical task. `ExecutionAttempt` is one concrete worker execution of that operation.

```text
Operation THUMBNAIL
    |
    +-- Attempt 1 worker-a INTERRUPTED
    |
    +-- Attempt 2 worker-b COMPLETED   → Operation COMPLETED
```

The scheduler selects the worker **before** RabbitMQ delivery. The assignment JSON records that worker (`workerId`) and policy (`FIFO`) but still does **not** include `attemptId`. Ownership is established only at start:

```text
Go scheduler: choose operation + worker
Java: Operation QUEUED -> ASSIGNED, persist scheduling_decisions, targeted outbox
publisher: routing key worker.{workerId}
selected worker receives v2 assignment
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
outbox row for that operation is cleared
    ↓
Go scheduler sees QUEUED again, FIFO selects it
    ↓
worker-a is UNAVAILABLE so it is not eligible
    ↓
lex-first remaining AVAILABLE capable worker (for example worker-b)
    ↓
worker-b start -> Attempt 2
```

The operation is requeued rather than left in a lasting `INTERRUPTED` status. The job stays `RUNNING` if other operations are still unfinished; a single requeued operation can make the job `QUEUED` again until the scheduler assigns it.

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

### Phase 4A limitations

- only FIFO operation ordering; no Round Robin, Least Loaded, SJF, EDF, or adaptive scoring
- worker placement is lexicographic among eligible workers, not load-aware
- FIFO ignores persisted priority and deadline
- no runtime estimator, queue-wait prediction, or utilization telemetry
- no CPU/memory scoring even though static cores/memory are registered
- no OpenTelemetry / Prometheus / Grafana / Jaeger
- no benchmark framework
- worker queues are not deleted when a worker becomes `UNAVAILABLE`
- `ASSIGNED` operations with no attempt are not auto-requeued if the selected worker never returns (durable queue may still be consumed on restart)
- the legacy Java enqueue path remains for tests (`drive.dispatch.scheduling-enabled`); keep it off in production

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

Pushes to non-`main` branches run **Branch CI**. Pull requests to `main` and pushes/merges to `main` run **PR / Main CI**. Java CI executes `./mvnw clean test` from `Server/drive`. The **Go tests** job runs `go vet` / `go test` in `worker/` and `scheduler/`. The existing required-check names **Java tests** and **Go tests** are unchanged.

These workflows are a **build/test gate**. They do not deploy anything. Deployment will be designed later.

See [docs/github-workflow.md](docs/github-workflow.md) for the full flow, the local CI equivalent, and the recommended GitHub settings to protect `main` (requiring a PR, requiring **Java tests**, blocking force pushes). Creating the YAML files does not enable those settings by itself.

## What comes later

The smallest next milestone is **Round Robin worker placement** on the same scheduler boundary (Phase 4B). FIFO operation ordering stays; only the worker-selection rule changes. Least Loaded, SJF, EDF, runtime estimation, utilization telemetry, and OpenTelemetry remain later still.
