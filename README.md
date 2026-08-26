# Adaptive Distributed Media Processing Platform

This repository is evolving from the original **Automated Video Processor** into a new portfolio project:

**Adaptive Distributed Media Processing Platform** — a distributed system that will eventually schedule heterogeneous media-processing jobs across workers based on workload characteristics, worker resources, load, priority, and deadlines.

This repository is currently at **Phase 4D.1**: FIFO still chooses the next operation. Worker placement can be lexicographic, Round Robin, or Least Loaded. Executable operations are **METADATA**, **THUMBNAIL**, and **AUDIO_EXTRACTION**. Transcode types remain queued only. SJF, EDF, and adaptive scoring are not implemented.

## Current status: Phase 4D.1 — AUDIO_EXTRACTION as a real media capability

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
contracts/operation-assignment.v2.schema.json   (obsolete targeted envelope; rejected at runtime)
contracts/operation-assignment.v3.schema.json   (current targeted placement + assignmentId)
```

Phase 4D.1 currently:

- accepts job submissions and persists `Job` + `Operation` records in PostgreSQL (`POST /jobs` stays a fast DB write and does **not** publish RabbitMQ)
- a **Go scheduler** polls `GET /internal/scheduler/snapshot`, selects the oldest eligible operation (**FIFO**), then chooses a worker with **LEXICOGRAPHIC**, **ROUND_ROBIN**, or **LEAST_LOADED** placement
- Java revalidates correctness in one transaction: operation still `QUEUED`, worker `AVAILABLE`, worker advertises the type (and Round Robin cursor when that policy is used), then `QUEUED -> ASSIGNED`, writes a `scheduling_decisions` row (`operationPolicy=FIFO`, `workerPolicy=...`), and creates a **worker-targeted** outbox row. Least Loaded is **not** re-checked for optimality at commit.
- the Java outbox publisher sends that assignment to RabbitMQ with routing key `worker.{workerId}`
- Go workers declare durable per-worker queues before they register, consume only their queue, and reject a v3 assignment whose `workerId` does not match
- workers send **periodic heartbeats**; the control service marks them `AVAILABLE` or `UNAVAILABLE`
- `GET /workers` lists workers, static capabilities, `status`, and `lastHeartbeat`
- workers call `POST /internal/operations/{id}/start` with `workerId` and `assignmentId` so PostgreSQL creates an `ExecutionAttempt` only for the **current** placement
- workers renew that lease independently of heartbeats while media work runs
- if a selected worker never starts, assignment timeout plus `UNAVAILABLE` returns the operation to `QUEUED` for a new scheduler placement
- a delayed old assignment is rejected (`409 STALE_ASSIGNMENT`) and cannot create an attempt
- if a worker becomes `UNAVAILABLE` **after** start and its attempt lease expires, the attempt is `INTERRUPTED`, the operation is `QUEUED`, and the Go scheduler can place it on another eligible worker
- a late result from an old attempt is rejected (`409 STALE_EXECUTION_ATTEMPT`)
- downloads `s3://` inputs (and still accepts `file://`)
- runs **real ffprobe** and **real FFmpeg**
- uploads JPEG thumbnails and AAC/M4A audio extracts to `s3://media-output/...` and persists `Artifact` metadata

FIFO is a **control baseline** for operation order, not a performance claim. It does not use job priority, deadline, CPU, memory, queue depth, or runtime estimates.

Worker placement is a separate dimension:

```text
LEXICOGRAPHIC  (default)  first eligible worker ID
ROUND_ROBIN               rotate among currently eligible workers per operation type
LEAST_LOADED              fewest RUNNING attempts, then workerId ASC
```

Least Loaded is a baseline that reacts to current executing work. It is not a throughput or latency claim. Round Robin ignores current work and rotates. Lexicographic is the deterministic Phase 4A baseline.

| Operation policy | Worker policy |
| ---------------- | ------------- |
| FIFO             | LEXICOGRAPHIC |
| FIFO             | ROUND_ROBIN   |
| FIFO             | LEAST_LOADED  |

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
  ^              <--- start (assignmentId + workerId; creates ExecutionAttempt + lease)
  ^              <--- renew / complete / fail (attemptId required)
  ^              <--- scheduling_decisions + targeted dispatch_outbox
  |
  | stale-heartbeat sweeper
  |   AVAILABLE -> UNAVAILABLE when lastHeartbeat is older than timeout
  |
  | unstarted-assignment sweeper
  |   ASSIGNED, no attempt, assignedAt expired, selected worker UNAVAILABLE
  |     -> operation QUEUED (outbox row deleted)
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
  +--> snapshot --> FIFO operation --> LEXICOGRAPHIC / ROUND_ROBIN / LEAST_LOADED worker --> assign
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
OPERATION_POLICY=FIFO \
WORKER_PLACEMENT_POLICY=LEAST_LOADED \
go run ./cmd/scheduler
```

`OPERATION_POLICY` must be `FIFO`. `WORKER_PLACEMENT_POLICY` is `LEXICOGRAPHIC` (default), `ROUND_ROBIN`, or `LEAST_LOADED`. Unknown values fail startup. `SCHEDULING_POLICY=FIFO` is still accepted as an alias for operation ordering only. Then start workers (see below).

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

`inputUri` is stored as a URI string. The public API does **not** contact S3 or verify that the object exists. Dispatch executes `file://` and `s3://` for `METADATA`, `THUMBNAIL`, and `AUDIO_EXTRACTION`. Other submitted types remain `QUEUED`. A job is not `COMPLETED` while those remain.

Supported operation types for submission: `METADATA`, `THUMBNAIL`, `AUDIO_EXTRACTION`, `TRANSCODE_1080P`, `TRANSCODE_4K_TO_1080P`, `H264_TO_AV1`.

**Executable operations:** `METADATA`, `THUMBNAIL`, `AUDIO_EXTRACTION`.

**Not executable yet:** `TRANSCODE_1080P`, `TRANSCODE_4K_TO_1080P`, `H264_TO_AV1`. Those remain `QUEUED`. The scheduler places executable types onto a specific worker; registration and capability matching prevent dispatch to a worker that did not advertise the type.

## Scheduler

The scheduler decides **placement**. RabbitMQ **transports** that placement. The worker **executes** it.

```text
queued operation
      ↓
Go scheduler (FIFO operation + LEXICOGRAPHIC, ROUND_ROBIN, or LEAST_LOADED worker)
      ↓
POST /internal/scheduler/assign
      ↓
Java transaction: validate, QUEUED -> ASSIGNED, scheduling_decisions, targeted outbox
      ↓
publisher -> routing key worker.{workerId}
      ↓
only that worker's queue receives the message
      ↓
POST /internal/operations/{id}/start  (current assignmentId + workerId; then attempt + lease)
```

### FIFO (operation ordering)

Scheduling unit is **Operation**, not whole Job. FIFO means the oldest **schedulable operation across jobs**:

```text
createdAt ASC, operationOrder ASC, id ASC
```

Job `priority` and `deadline` are persisted but **intentionally ignored** so FIFO stays a pure baseline. This phase does not skip an older unschedulable operation to run a younger one; if the oldest queued `THUMBNAIL` has no eligible worker, it stays `QUEUED` and the scheduler logs `no_eligible_worker` (no hot loop — it sleeps `SCHEDULER_POLL_INTERVAL`). Worker registration/recovery may make it schedulable later. The operation is not failed.

### Worker placement

After FIFO picks the operation, placement considers only workers that are `AVAILABLE` and advertise the type.

**LEXICOGRAPHIC** (default): sort eligible IDs and take the first. Example: `worker-a` and `worker-b` both eligible → `worker-a`.

**ROUND_ROBIN**: among that eligible set, take the next ID after the last **committed** Round Robin decision for the same operation type, wrapping to the first. If there is no previous RR decision, start at the lexicographically first eligible worker.

**LEAST_LOADED**: among currently eligible workers, choose the fewest `RUNNING` `ExecutionAttempt` rows. Tie-break is `workerId ASC`. `ASSIGNED` operations that have not started are **not** load. Completed, failed, and interrupted attempts are not load. Counts come from PostgreSQL (`COUNT` of attempts with `status = RUNNING` grouped by `worker_id`), not from a worker-reported counter.

CPU/memory utilization is **not** used. Instantaneous cross-platform CPU percentage is easy to fake and hard to measure truthfully; static `cpuCores` / `memoryBytes` are capacity, not load. A simple authoritative `activeOperations` count is the Phase 4C baseline. Weighted or utilization scoring is later work.

Example: worker-a active=2, worker-b active=0, worker-c active=1 → worker-b. Tie of active=1 between worker-a and worker-b → worker-a.

The scheduler snapshot includes `activeOperations` per worker. Public `GET /workers` does not. Java does **not** reject a Least Loaded proposal because another worker became slightly less loaded between snapshot and commit. That would thrash. Java still requires `QUEUED`, `AVAILABLE`, and capability.

Concurrent scheduler instances can both snapshot two idle workers and assign different operations to the same worker before either starts. That is accepted for this baseline: load is current `RUNNING` work, not a reservation of `ASSIGNED` work. Do not expect `a,b,a,b` under concurrent scheduling; that is Round Robin-like reservation behavior.

Eligibility is always `AVAILABLE` + capable, for every placement policy. A metadata-only worker is never selected for `THUMBNAIL` even if it is idle. An `UNAVAILABLE` worker is never selected even if `activeOperations` is 0.

Round Robin: `METADATA`, `THUMBNAIL`, and `AUDIO_EXTRACTION` rotate independently using committed RR history. `UNAVAILABLE` workers leave the current rotation and may rejoin later; there is no downtime-compensation credit. Round Robin ignores current executing work.

Least Loaded does not rotate and does not use the RR cursor. It compares current `RUNNING` counts among the eligible set. After lease or assignment-timeout recovery requeues work, the next tick places it among currently eligible workers using the same rule.

Cursor state is the latest `scheduling_decisions` row with `worker_policy=ROUND_ROBIN` for that type. Scheduler restarts keep rotating; they do not reset to `worker-a`. Failed or missing assignments do not advance the cursor. Java serializes RR commits with a transaction-scoped advisory lock and rejects a proposal that is not the current next worker (`409 WORKER_PLACEMENT_CONFLICT`).

Round Robin is not load-aware and is not an optimality claim.

Java also revalidates worker existence, `AVAILABLE`, and capability. A stale snapshot is rejected (`409`), and the scheduler continues.

Two scheduler processes may propose the same operation; the Job row lock allows only one `QUEUED -> ASSIGNED`. The loser gets `409` and retries the next loop. For Round Robin, two processes proposing the same next worker for different operations are serialized so committed history is `a, b` rather than `a, a`.

### Targeted RabbitMQ

| Name | Value |
| --- | --- |
| Exchange | `media.operations` (direct, durable) |
| Worker queue | `media.worker.{workerId}` (durable, worker-declared) |
| Routing key | `worker.{workerId}` |
| Dead-letter exchange | `media.operations.dlx` |
| Dead-letter queue | `media.operations.execute.dlq` |

Workers declare their queue **before** registration so they are not marked `AVAILABLE` with a missing queue. Offline `UNAVAILABLE` workers are not assigned. Durable queues are **not** deleted when a worker becomes `UNAVAILABLE` (cleanup is later technical debt). Messages for a worker that dies after publish can sit on that durable queue until the worker returns or assignment recovery requeues the operation. An old delayed message cannot start a later placement: `/start` checks `assignmentId`. After a successful `start`, Phase 3D lease recovery still applies.

A scheduler snapshot can be stale (worker dies between snapshot and commit). Commit revalidation plus leases provide eventual progress; this phase does not eliminate every race.

The legacy Java enqueue loop (`drive.dispatch.scheduling-enabled=true`) can still select `QUEUED` work onto the old shared queue for tests. Production default is **off**. Do not run it together with the Go scheduler.

### Assignment v3

```json
{
  "schemaVersion": 3,
  "operationId": "...",
  "jobId": "...",
  "type": "THUMBNAIL",
  "inputUri": "s3://...",
  "workerId": "worker-a",
  "scheduledAt": "...",
  "policy": "FIFO",
  "workerPolicy": "ROUND_ROBIN",
  "assignmentId": "..."
}
```

No `attemptId`. `assignmentId` is `SchedulingDecision.id`. Ownership still begins at `start`, which must send the same `assignmentId`. The worker drops a v3 message whose `workerId` does not match `WORKER_ID`. Obsolete v2 messages are rejected so they cannot skip identity checks. This environment does not keep v2 compatibility.

## Workers

Requirements: Java 21, Docker (PostgreSQL + MinIO + RabbitMQ), Go, FFmpeg/ffprobe.

Generate a tiny local clip (do not commit large binaries). Video-only is enough for METADATA/THUMBNAIL. AUDIO_EXTRACTION needs an audio stream:

```bash
ffmpeg -y -f lavfi -i testsrc=duration=2:size=320x240:rate=30 -pix_fmt yuv420p /tmp/sample.mp4
ffmpeg -y -f lavfi -i testsrc=duration=2:size=320x240:rate=30 \
  -f lavfi -i sine=frequency=440:duration=2 \
  -pix_fmt yuv420p -c:v libx264 -c:a aac -shortest /tmp/audio-sample.mp4
```

Upload it to MinIO. With the AWS CLI:

```bash
AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin \
  aws --endpoint-url http://localhost:9000 s3 cp /tmp/sample.mp4 s3://media-input/sample.mp4
AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin \
  aws --endpoint-url http://localhost:9000 s3 cp /tmp/audio-sample.mp4 s3://media-input/audio-sample.mp4
```

Or with the MinIO client in Docker:

```bash
docker run --rm --network host -v /tmp/sample.mp4:/sample.mp4 -v /tmp/audio-sample.mp4:/audio-sample.mp4 minio/mc \
  sh -c 'mc alias set local http://localhost:9000 minioadmin minioadmin && mc cp /sample.mp4 local/media-input/sample.mp4 && mc cp /audio-sample.mp4 local/media-input/audio-sample.mp4'
```

Canonical object: `s3://media-input/sample.mp4`. Audio sample: `s3://media-input/audio-sample.mp4`.

Start PostgreSQL, MinIO, RabbitMQ, and the control service as above, then submit:

```bash
curl -sS -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "inputUri": "s3://media-input/audio-sample.mp4",
    "operations": [
      {"type": "METADATA"},
      {"type": "THUMBNAIL"},
      {"type": "AUDIO_EXTRACTION"}
    ]
  }'
```

Audio-only:

```bash
curl -sS -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "inputUri": "s3://media-input/audio-sample.mp4",
    "operations": [{"type": "AUDIO_EXTRACTION"}]
  }'
```

The job is `QUEUED` until the scheduler assigns an operation (`ASSIGNED`), a worker starts one (`RUNNING` + `ExecutionAttempt`), and results are persisted (`COMPLETED` / `FAILED`). Interrupted infrastructure failures requeue the operation; the scheduler places it again. Attempt history is retained.

`WORKER_ID` is **required** (stable identity such as `worker-a`). The worker probes local executables and machine info, **declares its durable RabbitMQ queue**, registers, starts a heartbeat loop, and only then consumes `media.worker.{WORKER_ID}`. After `start` succeeds it also runs a **lease-renewal loop** for that attempt until complete/fail. `supportedOperations` means the worker has an implemented executor **and** the required local binary is available (`METADATA` needs ffprobe; `THUMBNAIL` and `AUDIO_EXTRACTION` need FFmpeg). Optional `SUPPORTED_OPERATIONS` may **restrict** that set; it cannot add unimplemented types. If a requested operation's executable is missing, startup fails: the worker does not register, does not heartbeat, and does not consume. Metadata-only workers (`SUPPORTED_OPERATIONS=METADATA`) do not require FFmpeg; encoder `supportedCodecs` stay empty in that case.

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

Observe worker logs for `event=registered`, then `event=consuming queue=media.worker.{id}`, `event=received`, `event=execution_start`, `event=execution_completed` / `event=execution_failure`, and `event=ack` / `event=nack_requeue`. A v3 assignment whose `workerId` does not match is dropped (`event=worker_id_mismatch`). A stale recovered assignment is dropped (`event=stale_assignment_start_rejected`) and does not run media. An assignment this worker did not advertise (for example `TRANSCODE_1080P`) is not executed; the message is dead-lettered (`event=capability_mismatch`). Scheduler logs include `event=assigned` and `event=no_eligible_worker`.

`METADATA` stores parsed probe JSON on the operation. `THUMBNAIL` extracts one JPEG frame (seek ~1s, falling back to the first frame on short clips) and uploads:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/thumbnail.jpg
```

`AUDIO_EXTRACTION` runs FFmpeg to produce AAC in an M4A container (`-vn -map 0:a -c:a aac -b:a 192k`). Content type is `audio/mp4`. Canonical object:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/audio.m4a
```

Inputs with no audio stream fail the operation (`FAILED`) with reason `input has no audio stream`. Empty output is rejected. Codec/bitrate are not configurable in this phase.

`GET /jobs/{id}/artifacts` returns type (`THUMBNAIL` or `AUDIO`), object URI, content type, size, and SHA-256 checksum. Media bytes stay in MinIO.

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

`AUDIO_EXTRACTION` runs FFmpeg to produce AAC in an M4A container (`-vn -map 0:a -c:a aac -b:a 192k`). Content type is `audio/mp4`. Canonical object:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/audio.m4a
```

Inputs with no audio stream fail the operation (`FAILED`) with reason `input has no audio stream`. Empty output is rejected. Codec/bitrate are not configurable in this phase.

`GET /jobs/{id}/artifacts` returns type (`THUMBNAIL` or `AUDIO`), object URI, content type, size, and SHA-256 checksum. Media bytes stay in MinIO.

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

The scheduler selects the worker **before** RabbitMQ delivery. The assignment JSON records that worker (`workerId`), policy (`FIFO`), and `assignmentId` (`SchedulingDecision.id`). It still does **not** include `attemptId`. Ownership is established only at start:

```text
Go scheduler: choose operation + worker
Java: Operation QUEUED -> ASSIGNED, persist scheduling_decisions, assigned_at, current_assignment_id, targeted outbox
publisher: routing key worker.{workerId}
selected worker receives v3 assignment
    ↓
POST /internal/operations/{id}/start  {"workerId":"worker-a","assignmentId":"<decision-id>"}
    ↓
control service (Job row lock):
    worker exists, AVAILABLE, advertises the type
    Operation ASSIGNED
    assigned worker matches
    current assignmentId matches
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

Unstarted assignment recovery (before any ExecutionAttempt exists):

```text
OPERATION_ASSIGNMENT_START_TIMEOUT=15s
OPERATION_ASSIGNMENT_SWEEP_INTERVAL=5s
```

These are **not** the lease settings. Assignment timeout applies only while the operation is `ASSIGNED` with no attempt. Lease duration applies only after `/start`.

Worker:

```text
LEASE_RENEW_INTERVAL=10s
```

Keep `LEASE_RENEW_INTERVAL` less than half of `OPERATION_LEASE_DURATION`. Transient renew failures are logged and retried on the next interval; they do not kill media work. If renewals stop and the worker is later `UNAVAILABLE`, the control plane may reclaim the attempt. A late `complete`/`fail` from that attempt is then `409 STALE_EXECUTION_ATTEMPT`.

### Recovery after worker failure

There are two separate failure windows.

#### Before start

```text
Operation ASSIGNED
no ExecutionAttempt
assignment timeout expires
selected worker UNAVAILABLE
    ↓
sweeper: assignment_expired
    ↓
Operation QUEUED, assignment fields cleared, dispatch_outbox row deleted
scheduling_decisions row kept for history
    ↓
Go scheduler sees QUEUED again, FIFO selects it
    ↓
current worker policy places it on an eligible worker
    ↓
new SchedulingDecision + targeted assignment
```

An expired assignment on an `AVAILABLE` worker is **not** reclaimed. That avoids requeueing while a delayed message is still about to be consumed.

#### After start

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
Go scheduler sees QUEUED again, FIFO selects the operation
    ↓
worker-a is UNAVAILABLE so it is not eligible
    ↓
current worker policy places it (lex-first, or next RR worker among remaining)
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

Start requires the current assignment. If `assignmentId` or `workerId` does not match the live placement:

- HTTP `409` `STALE_ASSIGNMENT`
- no ExecutionAttempt created
- no media execution on the worker (the message is dropped)

A delayed worker-a message cannot start after the operation has been reassigned to worker-b. Delivery is **at-least-once**, not exactly-once.

### Artifact retries

Thumbnail object keys stay `s3://media-output/jobs/<jobId>/operations/<operationId>/thumbnail.jpg`. Audio keys stay `s3://media-output/jobs/<jobId>/operations/<operationId>/audio.m4a`. A retry may overwrite the same key. If a worker uploads then dies before `complete` persists, the object can exist without an Artifact row. That orphan is **not** garbage-collected in this phase. Execution is **at-least-once**, not exactly-once.

`GET /jobs/{jobId}/operations/{operationId}/attempts` is a read-only history API (no lease internals).

### Phase 4D.1 limitations

- transcode operations are not executable: TRANSCODE_1080P, TRANSCODE_4K_TO_1080P, H264_TO_AV1
- AUDIO_EXTRACTION is a fixed AAC/M4A extract; no codec/bitrate API
- only FIFO operation ordering; no SJF, EDF, or adaptive scoring
- worker placement is LEXICOGRAPHIC, ROUND_ROBIN, or LEAST_LOADED; not weighted or adaptive
- Least Loaded counts RUNNING attempts only; ASSIGNED-not-started work is not reserved
- Least Loaded and Round Robin are not throughput or latency claims
- FIFO ignores persisted priority and deadline
- no runtime estimator, queue-wait prediction, or utilization telemetry
- no CPU/memory scoring even though static cores/memory are registered
- no OpenTelemetry / Prometheus / Grafana / Jaeger
- no benchmark framework
- worker queues are not deleted when a worker becomes `UNAVAILABLE`
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

The smallest next milestone is **TRANSCODE_1080P** as another real media capability on the same distributed path. A scheduling benchmark harness, SJF, EDF, runtime estimation, richer utilization telemetry, and OpenTelemetry remain later still.
