# Adaptive Distributed Media Processing Platform

This repository is evolving from the original **Automated Video Processor** into a new portfolio project:

**Adaptive Distributed Media Processing Platform** — a distributed system that will eventually schedule heterogeneous media-processing jobs across workers based on workload characteristics, worker resources, load, priority, and deadlines.

This repository is currently at **Phase 6B**: the Compose product from Phase 5G, operator observability from Phase 6A, plus a user-facing Job timeline assembled from persisted Job/Operation/attempt/decision/artifact history. User-facing APIs require an Account API key with ownership isolation. Scheduler/worker calls to `/internal/**` use separate internal service credentials. Users can list owned Jobs, inspect a chronological timeline of what happened, and request a **time-limited HTTP URL** for an owned Artifact. They can cancel work and explicitly retry **FAILED** operations. FIFO still chooses the next operation. Worker placement can be lexicographic, Round Robin, or Least Loaded. Executable operations are **METADATA**, **THUMBNAIL**, **AUDIO_EXTRACTION**, **TRANSCODE_1080P**, and **H264_TO_AV1**. `TRANSCODE_4K_TO_1080P` is retired. SJF, EDF, adaptive scoring, Kubernetes, and cloud-provider deploy are not implemented.

## Current status: Phase 6B — Job Timeline & Execution Details API

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

Phase 6B currently:

- includes everything from Phase 6A
- exposes `GET /jobs/{jobId}/timeline` so an Account can read a chronological explanation of an owned Job from PostgreSQL (not from Jaeger)
- keeps `GET /jobs/{id}/operations` and `GET /jobs/{id}/operations/{operationId}/attempts` as structured resource APIs; timeline is complementary
- does **not** query the Collector, Jaeger, or Prometheus when serving the product timeline
- does **not** add an event-sourcing table, frontend, WebSocket/SSE, or timeline search

Phase 6A currently:

- includes everything from Phase 5G (Compose, HTTPS ingress, API-key auth, internal service auth, cancel/retry, five media operations)
- exports OpenTelemetry traces and metrics from the control service, scheduler, and workers over OTLP
- runs a local Collector that writes traces to Jaeger and metrics to Prometheus (Grafana optional dashboards)
- keeps media processing running if the telemetry backend is down

Phase 5G currently:

- launches the full platform with `docker compose up --build` (Java, scheduler, two workers, PostgreSQL, RabbitMQ, MinIO, Caddy HTTPS ingress)
- serves the public API over **HTTPS** at `https://localhost` (Caddy local TLS; Java stays HTTP on the Compose network)
- authenticates users with `Authorization: Bearer <api-key>` on `/jobs/**`, `/workers/**`, `/api-keys/**`

- stores API keys as SHA-256 hashes (raw keys are shown only when created)
- scopes Job listing, inspection, **timeline**, cancel, retry, artifacts, and download URLs to the authenticated Account
- authenticates `/internal/**` with scheduler and per-worker service tokens (not Account API keys)
- can disable open `POST /accounts` registration (`ACCOUNT_REGISTRATION_ENABLED`, default false)

- accepts job submissions and persists `Job` + `Operation` records in PostgreSQL (`POST /jobs` stays a fast DB write and does **not** publish RabbitMQ)
- lists Jobs with PostgreSQL pagination, deterministic newest-first ordering, and AND filters (`GET /jobs`)
- lets users inspect one Job, its **timeline**, operations, execution attempts, and artifacts without embedding the entire graph in the list payload
- issues a time-limited HTTP download URL for an Artifact (`POST /jobs/{jobId}/artifacts/{artifactId}/download-url`) without returning object-store credentials
- lets users cancel a job (`POST /jobs/{id}/cancel`) or one operation (`POST /jobs/{id}/operations/{operationId}/cancel`) without deleting history
- lets users explicitly retry a **FAILED** operation (`POST /jobs/{id}/operations/{operationId}/retry`) or every FAILED operation in a job (`POST /jobs/{id}/retry`)
- retry returns the operation to `QUEUED` for normal FIFO scheduling; it does not publish RabbitMQ, create an attempt, or change input URI
- old `ExecutionAttempt` and `SchedulingDecision` rows remain; the next `/start` creates the next attempt number
- cancels `QUEUED` and `ASSIGNED` work immediately; a delayed RabbitMQ assignment cannot start a cancelled operation
- records `CANCEL_REQUESTED` for `RUNNING` work, tells the owning worker on the next lease renew, and only then marks the attempt and operation `CANCELLED`
- actually stops the FFmpeg/ffprobe process; cancelled execution cannot persist a new Artifact
- does **not** requeue user-cancelled work when a lease later expires
- a **Go scheduler** polls `GET /internal/scheduler/snapshot`, selects the oldest eligible operation (**FIFO**), then chooses a worker with **LEXICOGRAPHIC**, **ROUND_ROBIN**, or **LEAST_LOADED** placement
- Java revalidates correctness in one transaction: operation still `QUEUED`, worker `AVAILABLE`, worker advertises the type (and Round Robin cursor when that policy is used), then `QUEUED -> ASSIGNED`, writes a `scheduling_decisions` row (`operationPolicy=FIFO`, `workerPolicy=...`), and creates a **worker-targeted** outbox row. Least Loaded is **not** re-checked for optimality at commit.
- the Java outbox publisher sends that assignment to RabbitMQ with routing key `worker.{workerId}`
- Go workers declare durable per-worker queues before they register, consume only their queue, and reject a v3 assignment whose `workerId` does not match
- workers send **periodic heartbeats**; the control service marks them `AVAILABLE` or `UNAVAILABLE`
- `GET /workers` lists workers, static capabilities, `status`, and `lastHeartbeat`
- workers call `POST /internal/operations/{id}/start` with `workerId` and `assignmentId` so PostgreSQL creates an `ExecutionAttempt` only for the **current** placement
- workers renew that lease independently of heartbeats while media work runs; renew responses can request cancellation
- if a selected worker never starts, assignment timeout plus `UNAVAILABLE` returns the operation to `QUEUED` for a new scheduler placement
- a delayed old assignment is rejected (`409 STALE_ASSIGNMENT`) and cannot create an attempt
- if a worker becomes `UNAVAILABLE` **after** start and its attempt lease expires, the attempt is `INTERRUPTED`, the operation is `QUEUED`, and the Go scheduler can place it on another eligible worker — unless cancellation was already requested, in which case both become `CANCELLED`
- a late result from an old attempt is rejected (`409 STALE_EXECUTION_ATTEMPT`)
- downloads `s3://` inputs (and still accepts `file://`)
- runs **real ffprobe** and **real FFmpeg**
- uploads JPEG thumbnails, AAC/M4A audio extracts, H.264 MP4 transcodes, and AV1 MP4 conversions to `s3://media-output/...` and persists `Artifact` metadata

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
                         HTTPS
                           |
                           v
                    Reverse Proxy
                           |
                           v
                   Java Control Plane
                    /       |       \
                   /        |        \
            PostgreSQL  Go Scheduler  Worker Registry
                            |
                         RabbitMQ
                      /             \
                     v               v
                 Worker A         Worker B
                     \               /
                      \             /
                         MinIO/S3

Java / Scheduler / Worker
          |
         OTLP
          v
 OpenTelemetry Collector
      /            \
     v              v
  Jaeger        Prometheus
                    |
                    v
                 Grafana
```

```text
Client
  |
  v
HTTPS ingress (Caddy)
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
  ^              <--- renew (leaseExpiresAt + cancelRequested) / complete / fail / cancelled
  ^              <--- POST /jobs/{id}/cancel and POST /jobs/{id}/operations/{operationId}/cancel
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
  |   CANCEL_REQUESTED + expired lease
  |     -> attempt CANCELLED, operation CANCELLED (not requeued)
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

Stack: **Java 21**, **Spring Boot 4.1.1**, **Maven**, **PostgreSQL**, **Flyway**, **Spring Data JPA**, **Spring AMQP**, **Go**, **amqp091-go**, **ffprobe/FFmpeg**, **MinIO**, **RabbitMQ**, **OpenTelemetry**, **Jaeger**, **Prometheus**, **Grafana**. The Maven `artifactId` remains `drive`.

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

Java tests start a temporary PostgreSQL container. Dispatcher and scheduler RabbitMQ tests also start RabbitMQ via Testcontainers. They do **not** require the Compose database, MinIO, or Compose RabbitMQ. Some Go tests generate a tiny clip with FFmpeg when `ffmpeg`/`ffprobe` are on `PATH`; they are skipped if those binaries are missing. GitHub Actions does **not** install FFmpeg or MinIO. Object-storage unit tests use an in-memory fake. Worker broker tests start RabbitMQ via Testcontainers. Scheduler tests are unit tests (no Docker). CI also runs `docker compose --env-file .env.example config`.

## Prerequisites

For the normal product path you only need:

```text
Docker
Docker Compose
```

Java, Go, and FFmpeg are inside the images. Host-based development still uses JDK 21, Go, and FFmpeg if you run processes on the host (see that section below).

## Quickstart

```bash
./scripts/setup-local-env.sh
docker compose up --build
```

`setup-local-env.sh` writes an untracked `.env` with random local secrets (scheduler token, worker pepper, worker-a/worker-b tokens, bootstrap Account API key). It will not overwrite an existing `.env` unless you pass `--force`. Those values are **local development secrets**, not production secrets. Production should use a real secret store later; this phase does not integrate one.

Wait until `control-service` is healthy, then:

```bash
curl -k https://localhost/health
```

Expected: `{"status":"UP"}`. `GET /health` is public.

Load the bootstrap Account key (the only key you need for first-run; open `POST /accounts` stays **disabled**):

```bash
set -a && source .env && set +a
export MEDIA_PLATFORM_API_KEY="$MEDIA_PLATFORM_BOOTSTRAP_API_KEY"
```

```bash
curl -k -sS \
  -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" \
  https://localhost/jobs
```

HTTP on port 80 redirects to HTTPS. The authenticated product API is not served as public plaintext HTTP.

## Observability

Operator traces and metrics explain where a Job spent time:

```text
submission → persistence → scheduling → assignment → queue wait
→ worker start → FFmpeg/ffprobe → object storage → completion
```

This is not a substitute for the product timeline. Jaeger, Prometheus, and Grafana are **local operator tools**. They are bound to localhost and are **not** exposed through Caddy. `GET /jobs/{jobId}/timeline` is the Account-scoped product explanation and stays correct if telemetry backends are down.

```text
Product timeline (GET /jobs/{id}/timeline)
------------------------------------------
persisted PostgreSQL rows
user-facing, Account-scoped
stable business lifecycle
available even if telemetry is down

Operator trace (Jaeger)
-----------------------
OpenTelemetry spans
operator-facing, sampled/ephemeral
implementation path and cross-service debugging
not the source of truth for Job history
```

```text
Java / scheduler / worker
        ↓ OTLP
OpenTelemetry Collector
        ├── traces → Jaeger   (http://127.0.0.1:16686)
        └── metrics → Prometheus (http://127.0.0.1:9090)
                          ↓
                       Grafana (http://127.0.0.1:3000)
```

Grafana anonymous Viewer is enabled for local use (`admin`/`admin` if you sign in). Do not publish these ports.

### Starting the stack

`docker compose up --build` starts the product and the observability backends. Product processing does **not** wait on Collector health. If Collector/Jaeger/Prometheus are down, Jobs still complete.

### How to find a slow Job

1. Note the Job id from `POST /jobs` / `GET /jobs/{id}`.
2. Open Jaeger, service `media-control-service`, search tags `media.job.id=<id>`.
3. Follow the trace across `media-scheduler`, `rabbitmq.publish`, `media-worker`, FFmpeg, object storage, and complete/fail.
4. Public HTTP responses also include a `traceparent` header (W3C). The trace id is the 32-hex middle field.

The original `POST /jobs` trace is stored on the Job (not in the assignment JSON). The internal scheduler snapshot includes that W3C context so later placement continues the same trace. Heartbeats, lease renewals, idle snapshots, and empty outbox polls are not traced.

Queue wait is **assignedAt − queuedAt** (persisted). Assignment wait is **startedAt − assignedAt**. Job end-to-end latency is **terminal time − Job.createdAt** (Job has no dedicated completedAt; use `updatedAt` at the terminal transition, or the root span duration). Runtime in the API remains `actualRuntimeMs` / attempt timestamps; OTel does not replace those fields.

### Important metrics

| Metric | Meaning |
| --- | --- |
| `media_jobs_submitted_total` / `_completed_total` / `_failed_total` / `_cancelled_total` | Job lifecycle |
| `media_operations_completed_total{type}` / `_failed_total{type}` | Operation outcomes |
| `media_operation_runtime_seconds{type}` | Histogram from persisted `actualRuntimeMs` (control service) and live worker duration |
| `media_operation_queue_wait_seconds{type}` | `assignedAt - queuedAt` |
| `media_operation_assignment_wait_seconds{type}` | start − `assignedAt` |
| `media_worker_available` / `media_worker_running_operations` | Per-worker process gauges |
| `media_scheduler_decisions_total{worker_policy}` | Successful placements |

Labels are bounded (`type`, `worker_policy`). Job/operation/worker ids belong in traces and logs, not metric labels.

### Configuration

Standard-ish env vars:

```text
OTEL_EXPORTER_OTLP_ENDPOINT   base URL, no path: http://otel-collector:4318 (Compose) or http://localhost:4318
OTEL_EXPORTER_OTLP_PROTOCOL   http/protobuf (Collector HTTP on 4318)
OTEL_SERVICE_NAME             media-control-service | media-scheduler | media-worker
OTEL_TRACES_SAMPLER           parentbased_traceidratio | always_on | always_off | traceidratio
OTEL_TRACES_SAMPLER_ARG       1.0 locally; lower in production-style deploys
OTEL_TRACES_ENABLED           true/false
OTEL_METRICS_ENABLED          true/false
OTEL_SDK_DISABLED             true disables the Go SDK
```

Local Compose samples 100%. Heartbeats, lease renewals, idle scheduler snapshots, and empty outbox polls are not traced.

### Security / privacy

Traces and metrics must not include API keys, scheduler/worker tokens, worker pepper, `Authorization`, secret access keys, or presigned URL query strings. Input URIs are not recorded as span attributes. Observability UIs are not Account-authenticated in this phase.

Actuator Prometheus is **not** exposed. Metrics leave via OTLP. `/actuator/**` is not a public ingress route.

### Trace example

A completed METADATA Job typically shows:

```text
POST /jobs                    media-control-service
  job.persist
scheduler.snapshot            media-scheduler
scheduler.select_operation
scheduler.select_worker
scheduler.assign
  POST /internal/scheduler/assign   media-control-service
    scheduler.assign
    rabbitmq.publish
worker.consume                media-worker
  operation.start
  media.execute
    ffprobe.metadata
    objectstore.download   (s3 inputs)
  operation.complete          media-control-service
```

### What this phase does not add

No Loki/ELK, alerting, SLO framework, service mesh, or user-facing performance analysis API.


### Submit a job, download an artifact

Upload a small clip to MinIO (`http://127.0.0.1:9000`, buckets `media-input` / `media-output`), then:

```bash
curl -k -sS -X POST https://localhost/jobs \
  -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"inputUri":"s3://media-input/sample.mp4","operations":[{"type":"METADATA"},{"type":"THUMBNAIL"}]}'
```

List and inspect with the same HTTPS host. When a THUMBNAIL artifact exists:

```bash
curl -k -sS -X POST \
  -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" \
  https://localhost/jobs/<job-id>/artifacts/<artifact-id>/download-url
```

The JSON `url` is a **presigned MinIO GET** on `http://127.0.0.1:9000` (not `http://minio:9000`). Curl that URL from the host with **no** API key and **no** internal token. SigV4 is computed for that public host; the URL is not rewritten after signing.

Cancel and retry are unchanged: `POST /jobs/{id}/cancel` and `POST /jobs/{id}/operations/{id}/retry` through HTTPS.

### Stop, restart, reset

```bash
docker compose down          # keeps named volumes (Jobs, Accounts, artifacts, broker state)
docker compose up            # same data comes back
docker compose down -v       # destructive reset of Postgres, MinIO, RabbitMQ, and Caddy data
```

`docker compose restart control-service` preserves the database. Restarting a worker marks it UNAVAILABLE until it heartbeats again, then AVAILABLE.

### Local TLS

Caddy uses an **internal CA** (`tls internal`). Browsers will warn until you trust that CA. `curl -k` is enough for local API checks. To export Caddy's local root (after the ingress container has started once):

```bash
docker compose cp ingress:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt
```

Do not commit certificates. This is development TLS, not a public CA.

External client credentials **must** use HTTPS through ingress. Scheduler and worker bearer tokens stay on the private Compose network as **HTTP**. That traffic is **not** mTLS. This phase does not implement a service mesh.

### Object-store endpoints

| Setting | Used by | Compose value |
| --- | --- | --- |
| `OBJECT_STORE_ENDPOINT` | Java HEAD, worker Get/Put | `http://minio:9000` |
| `OBJECT_STORE_PUBLIC_ENDPOINT` | Java S3 presigner (client URLs) | `http://127.0.0.1:9000` |

Workers never receive `WORKER_TOKEN_PEPPER`. Each worker gets only its own `WORKER_SERVICE_TOKEN`.

### Host ports

Default product Compose publishes:

- **80 / 443** — HTTPS ingress (and HTTP→HTTPS redirect)
- **9000** — MinIO S3 API (presigned downloads from the host)

PostgreSQL and RabbitMQ AMQP stay on the Compose network so they do not collide with a host Postgres on 5432. Override `MINIO_API_PORT` / `INGRESS_HTTPS_PORT` in `.env` if those host ports are taken, and keep `OBJECT_STORE_PUBLIC_ENDPOINT` in sync with the published MinIO port.

### Worker scaling

`worker-a` and `worker-b` each have a bound token. `docker compose up --scale worker=20` does **not** work with this identity model. Add a new service (and mint a token) for `worker-c`. Dynamic provisioning is later work.

Worker image size is larger than the scheduler image because Debian FFmpeg plus `libx264` and a software AV1 encoder (`libaom-av1` or `libsvtav1`) must be present. The image build **fails** if those encoders are missing, so advertised `H264_TO_AV1` / `TRANSCODE_1080P` stay truthful.

### Shutdown

SIGTERM: Java uses Spring graceful shutdown (30s). The scheduler loop stops. A worker cancels its context, stops consuming, and nacks in-flight work for requeue unless the user already cancelled. A long AV1 encode that is still running is interrupted; lease/recovery requeues it if the worker is UNAVAILABLE. Compose `stop_grace_period` for workers is **30s**.

## Host-based development

Use this only when you are changing Java/Go on the host. The product path is Compose. Local HTTP here is **development-only**.

You can start just the data plane from an older workflow, but the current Compose file also builds the apps. For host processes, point them at published ports you add yourself, or run the data services by temporarily publishing ports. Defaults inside containers are service DNS names (`postgres`, `rabbitmq`, `minio`, `control-service`), not `localhost`.

Host Java still needs:

```text
ACCOUNT_REGISTRATION_ENABLED=true
SCHEDULER_SERVICE_TOKEN=...
WORKER_TOKEN_PEPPER=...
OBJECT_STORE_ENDPOINT=http://127.0.0.1:9000
```

Mint worker tokens with `python3 scripts/mint-worker-token.py <pepper> <worker-id>`. Java fails startup if the scheduler token or worker pepper is missing.

```bash
cd Server/drive
./mvnw spring-boot:run
```

```bash
cd scheduler
CONTROL_SERVICE_URL=http://localhost:8080 \
SCHEDULER_SERVICE_TOKEN=... \
go run ./cmd/scheduler
```

Host workers use `CONTROL_SERVICE_URL=http://localhost:8080`, `RABBITMQ_URL` to a published AMQP port, and `OBJECT_STORE_ENDPOINT` to the published MinIO API. API examples below that use `http://localhost:8080` are this host-dev path; the Compose product path is `https://localhost` with `curl -k` and the bootstrap key from `.env`.

## Health check

Product stack:

```bash
curl -k https://localhost/health
```

Host-dev:

```bash
curl http://localhost:8080/health
```

Expected response:

```json
{"status":"UP"}
```

`GET /health` is public. Load balancers and operators do not need an API key. It does not expose Jobs, keys, or store credentials.

## Authentication

This is a **developer/platform API-key** model for a self-hosted control plane, plus a separate internal service credential for scheduler/worker calls. It is not username/password login, JWT, OAuth, sessions, user RBAC, or organizations.

```text
Internet/client
    ↓
Account API key
    ↓
Public product API

Scheduler process
    ↓
Scheduler service credential
    ↓
Scheduler internal API

Worker process
    ↓
Worker service credential
    ↓
Worker internal API
```

No credential class may cross those boundaries. A user API key cannot call `/internal/**`. An internal token cannot call `/jobs`.

```text
User API:      Authorization: Bearer <account-api-key>
Scheduler:     Authorization: Bearer <scheduler-service-token>
Worker:        Authorization: Bearer <worker-service-token>
```

### Obtain a development key

`POST /accounts` is a self-hosted/development bootstrap. It is **disabled by default** (`ACCOUNT_REGISTRATION_ENABLED=false`). When disabled it returns **403** `ACCOUNT_REGISTRATION_DISABLED`. The Compose setup script issues `MEDIA_PLATFORM_BOOTSTRAP_API_KEY` instead of enabling open registration. Host-based development may set `ACCOUNT_REGISTRATION_ENABLED=true`. Open registration is not production identity administration and is not an admin RBAC subsystem.

```bash
curl -sS -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Studio A"}'
```

Example response (the `key` field appears only this once):

```json
{
  "account": {
    "id": "...",
    "name": "Studio A",
    "status": "ACTIVE",
    "createdAt": "2026-08-27T03:00:00Z"
  },
  "apiKey": {
    "id": "...",
    "key": "mp_live_...",
    "prefix": "mp_live_ab12",
    "createdAt": "2026-08-27T03:00:00Z"
  }
}
```

```bash
export MEDIA_PLATFORM_API_KEY='mp_live_...'   # paste the key from the create response
```

Jobs that existed before this phase belong to the Flyway `legacy-system` Account (`00000000-0000-0000-0000-000000000001`). To reach them, start the control service with a well-formed `MEDIA_PLATFORM_BOOTSTRAP_API_KEY` (local placeholder only):

```bash
MEDIA_PLATFORM_BOOTSTRAP_API_KEY=mp_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  ./mvnw spring-boot:run
```

An Account may have multiple keys (laptop, CI, integration). All keys for an Account have the same access. `POST /api-keys` (authenticated) issues another key. `GET /api-keys` returns metadata only (`id`, `prefix`, `createdAt`, `revokedAt`) — never the raw secret. `POST /api-keys/{id}/revoke` stops that key; later requests with it return **401**.

### Call product APIs

```bash
curl \
  -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" \
  http://localhost:8080/jobs
```

Missing, malformed, unknown, and revoked keys all return **401** `UNAUTHORIZED` with the same body. The response does not say why authentication failed.

```json
{
  "code": "UNAUTHORIZED",
  "message": "Authentication required",
  "timestamp": "2026-08-27T03:00:00Z"
}
```

Raw keys are never stored. PostgreSQL keeps a SHA-256 hex digest with a unique index, plus a short non-secret prefix for operators. API keys are 256-bit CSPRNG secrets, so SHA-256 is a lookup-friendly one-way store rather than a password-stretching problem. The prefix is not sufficient to authenticate.

`POST /jobs` assigns ownership from the authenticated Account. Clients cannot send `accountId`.

A caller who uses a valid key for Account A against Account B's Job, operation, attempt, or artifact receives **404** `JOB_NOT_FOUND` (same as a missing id). That avoids revealing that the resource exists. Lifecycle errors on **your** resource remain **409** (for example cancel after `COMPLETED`, retry when nothing is `FAILED`).

### Internal service authentication

Internal endpoints are a different trust domain from Account API keys.

| Caller | Env | Endpoints |
| --- | --- | --- |
| Go scheduler | `SCHEDULER_SERVICE_TOKEN` | `GET /internal/scheduler/snapshot`, `POST /internal/scheduler/assign` |
| Go worker | `WORKER_ID` + `WORKER_SERVICE_TOKEN` | register, heartbeat, start, renew, complete, fail, cancelled ack, and claim if enabled |

Multiple scheduler processes may share one scheduler token in this phase. There is no scheduler-instance table.

Workers use **per-worker HMAC tokens**. The control plane holds `WORKER_TOKEN_PEPPER` and never gives it to workers. A token looks like `mp_wk_<workerId>_<hmac-sha256-hex>` over `WORKER:<workerId>`. `worker-a`'s token cannot register, heartbeat, start, renew, complete, fail, or acknowledge cancellation as `worker-b`. Identity comes from the token subject, not only from the JSON body.

Mint tokens with `scripts/mint-worker-token.py`. Restart Java with a new pepper/scheduler token to rotate; credentials are environment-managed and are **not** stored in PostgreSQL (raw or hashed). The control process hashes the scheduler token in memory for comparison.

Missing or invalid internal credentials return **401** `UNAUTHORIZED` (same body as user auth; no enumeration). A valid internal credential of the **wrong service type** (worker token on scheduler APIs, or scheduler token on worker APIs) returns **403** `FORBIDDEN`. Worker identity mismatch also returns **403** and does not reveal the expected worker id.

`POST /internal/operations/claim` stays disabled by default. When tests enable it, it still requires a worker token bound to the request `workerId`.

This phase does **not** implement mTLS, SPIFFE, a service mesh, OAuth service accounts, or a secret manager.

### What this phase does and does not cover

Phase 5F protected the **user API** and the **internal scheduler/worker API** as separate trust domains. Phase 5G adds Compose + HTTPS ingress; it still does **not** solve:

- TLS inside Spring Boot (Caddy terminates TLS; Java stays HTTP on the Compose network)
- secret-manager integration
- user RBAC / organizations / teams
- automated token rotation
- audit logging
- rate limiting
- password, JWT, OAuth, or browser login

Presigned Artifact URLs remain **bearer capabilities until they expire**. Revoking an API key does **not** invalidate already-issued S3 signatures. Short TTL is the control. The signed MinIO/S3 URL does not require the Java API key or an internal service token; S3 validates the signature.

Do not log `Authorization` headers, raw API keys, internal tokens, token hashes, peppers, or full presigned URLs.

## Job Management API

Typical product flow:

```text
submit job
    ↓
list jobs
    ↓
filter by status / type / priority / created time
    ↓
inspect one job
    ↓
read the job timeline
    ↓
inspect operations / attempts / artifacts
    ↓
retrieve artifact identity (s3://bucket/key)
```

There is no web UI. User-facing Job APIs require `Authorization: Bearer $MEDIA_PLATFORM_API_KEY`. List results are **only Jobs owned by that Account**.

### List jobs

```bash
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" 'http://localhost:8080/jobs'
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" 'http://localhost:8080/jobs?status=COMPLETED'
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" 'http://localhost:8080/jobs?status=FAILED&operationType=H264_TO_AV1&priority=HIGH'
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" 'http://localhost:8080/jobs?page=0&size=2'
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" 'http://localhost:8080/jobs?createdAfter=2026-08-01T00:00:00Z&createdBefore=2026-08-31T23:59:59Z'
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" 'http://localhost:8080/jobs?sort=updatedAt&direction=asc'
```

Response shape:

```json
{
  "items": [
    {
      "id": "...",
      "inputUri": "s3://media-input/video.mp4",
      "status": "COMPLETED",
      "priority": "NORMAL",
      "deadline": null,
      "createdAt": "2026-08-26T20:00:00Z",
      "updatedAt": "2026-08-26T20:01:00Z",
      "operationCount": 3,
      "artifactCount": 2
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 87,
  "totalPages": 5
}
```

List items are summaries. They do not include operations, attempts, artifacts, or failure blobs. Use the Job detail and nested endpoints for that.

| Query | Default | Notes |
| --- | --- | --- |
| `page` | `0` | Zero-based. Negative values return **400** `VALIDATION_FAILED`. |
| `size` | `20` | Max **100**. `size=0`, negative, or `>100` return **400** `VALIDATION_FAILED`. |
| `sort` | `createdAt` | Allowed: `createdAt`, `updatedAt`. Anything else returns **400** `VALIDATION_FAILED`. |
| `direction` | `desc` | Allowed: `asc`, `desc`. Newest first by default. Tie-break is `id` in the same direction. |
| `status` | (none) | Existing `JobStatus` values. Invalid values return **400** `INVALID_ENUM_VALUE`. |
| `operationType` | (none) | Jobs that contain **at least one** operation of this type. Current types only; retired `TRANSCODE_4K_TO_1080P` is rejected. |
| `priority` | (none) | `LOW`, `NORMAL`, or `HIGH`. |
| `createdAfter` / `createdBefore` | (none) | Inclusive UTC Instants (`2026-08-01T00:00:00Z`). If `createdAfter` is after `createdBefore`, **400** `VALIDATION_FAILED`. |

Supplied filters are **AND**ed. Pagination and filtering run in PostgreSQL; the API does not load every Job into memory.

`operationCount` and `artifactCount` are filled with two grouped count queries for the current page (not per-row round-trips).

A `FAILED` summary is enough to call retry. Active statuses (`QUEUED`, `ASSIGNED`, `RUNNING`, `CANCEL_REQUESTED`) are enough to call cancel. The list does not embed action links.

### Get a job

```bash
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" http://localhost:8080/jobs/<job-id>
```

Returns the Job plus its operations (status, `queuedAt`, `failureReason` when present, result metadata). Also includes `operationCount` and `artifactCount`. Attempts and artifacts stay on their own endpoints so clients can fetch them when needed.

Unknown jobs, and Jobs owned by a different Account, return **404** `JOB_NOT_FOUND`.

### Job timeline

`GET /jobs/{jobId}/timeline` is the user-facing explanation of **what happened to this Job**. It is assembled from persisted rows (`jobs`, `operations`, `scheduling_decisions`, `execution_attempts`, `artifacts`). It does not query Jaeger, Prometheus, or the OpenTelemetry Collector.

```bash
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" http://localhost:8080/jobs/<job-id>/timeline
```

On Compose HTTPS ingress, use `https://localhost` with `curl -k` instead of `http://localhost:8080`.

Authentication and ownership match other Job resources: Bearer API key required; another Account receives **404** `JOB_NOT_FOUND` (no existence disclosure). The request is read-only: it does not update timestamps, retry work, mint download URLs, or rebuild Job status.

`GET /jobs/{id}/operations` remains the structured per-operation resource. `GET /jobs/{id}/operations/{operationId}/attempts` remains the detailed attempt history. Timeline is a chronological narrative over the same persisted facts.

Events are globally chronological (overlapping operations are interleaved, not grouped). Tie-break is:

```text
timestamp → event-type precedence → operation order → record id
```

Only events that current data can timestamp are emitted. `CANCEL_REQUESTED` has no dedicated column, so it is omitted rather than stamped with `updatedAt`. `JOB_RUNNING` is omitted for the same reason. Job `completedAt` is derived as the latest operation `completedAt` when the persisted Job status is already terminal.

Supported event types:

```text
JOB_CREATED
OPERATION_QUEUED          (operations.createdAt; stable across retry)
OPERATION_RETRIED         (queuedAt after a failed/interrupted attempt)
OPERATION_ASSIGNED        (each scheduling_decisions row)
OPERATION_STARTED         (each execution_attempts.startedAt)
ARTIFACT_CREATED
OPERATION_COMPLETED / OPERATION_FAILED / OPERATION_CANCELLED
JOB_COMPLETED / JOB_FAILED / JOB_CANCELLED
```

Per-operation latency uses the Phase 6A definitions. Missing timestamps yield `null` (omitted in JSON), never a negative or invented zero:

```text
queueWaitMs            = assignedAt − queuedAt
assignmentWaitMs       = startedAt − assignedAt
executionRuntimeMs     = attempt actualRuntimeMs (or endedAt − startedAt)
totalOperationLatencyMs = operation.completedAt − operation.createdAt
durationMs (job)       = job completedAt − job.createdAt
```

`assignedAt` on the operation is cleared when the worker starts, so assignment times come from `scheduling_decisions.createdAt` after start. Failure text is sanitized (no stack traces, signed URLs, tokens, or connection strings). Artifacts include id, type, size, and checksum — not a presigned URL. Optional `traceId` is parsed from the Job’s stored W3C `traceparent` when present; it is correlation only.

The response is not paginated. A Job currently has a bounded number of operations and attempts. Very long retry histories may need pagination later.

Example (abridged):

```json
{
  "jobId": "…",
  "status": "COMPLETED",
  "createdAt": "2026-08-27T20:00:00Z",
  "updatedAt": "2026-08-27T20:00:08.421Z",
  "completedAt": "2026-08-27T20:00:08.421Z",
  "durationMs": 8421,
  "operationCount": 2,
  "completedOperationCount": 2,
  "failedOperationCount": 0,
  "cancelledOperationCount": 0,
  "artifactCount": 1,
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "events": [
    {"timestamp": "2026-08-27T20:00:00Z", "type": "JOB_CREATED", "message": "Job created with 2 operations"},
    {"timestamp": "2026-08-27T20:00:00Z", "type": "OPERATION_QUEUED", "operationType": "METADATA", "operationOrder": 0},
    {"timestamp": "2026-08-27T20:00:00.240Z", "type": "OPERATION_ASSIGNED", "workerId": "worker-b", "operationPolicy": "FIFO", "workerPolicy": "LEAST_LOADED"},
    {"timestamp": "2026-08-27T20:00:00.271Z", "type": "OPERATION_STARTED", "attemptNumber": 1, "workerId": "worker-b"},
    {"timestamp": "2026-08-27T20:00:02.113Z", "type": "ARTIFACT_CREATED", "artifactType": "THUMBNAIL", "sizeBytes": 12345},
    {"timestamp": "2026-08-27T20:00:02.113Z", "type": "OPERATION_COMPLETED", "attemptNumber": 1, "runtimeMs": 1842},
    {"timestamp": "2026-08-27T20:00:08.421Z", "type": "JOB_COMPLETED", "message": "Job completed"}
  ],
  "operations": [
    {
      "type": "THUMBNAIL",
      "status": "COMPLETED",
      "queueWaitMs": 240,
      "assignmentWaitMs": 31,
      "executionRuntimeMs": 1842,
      "lastWorkerId": "worker-b",
      "attemptCount": 1
    }
  ]
}
```

Retry history is preserved: a failed attempt, `OPERATION_RETRIED`, a later decision, and a new attempt all appear. The attempts endpoint still has the full per-attempt rows.

### Operations, attempts, and artifacts

```bash
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" http://localhost:8080/jobs/<job-id>/operations
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" http://localhost:8080/jobs/<job-id>/operations/<operation-id>/attempts
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" http://localhost:8080/jobs/<job-id>/artifacts
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" http://localhost:8080/jobs/<job-id>/artifacts/<artifact-id>
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" -X POST http://localhost:8080/jobs/<job-id>/artifacts/<artifact-id>/download-url
```

Artifact JSON uses the existing field names: `id`, `operationId`, `type`, `objectUri`, `contentType`, `sizeBytes`, `checksum`, `createdAt`. `objectUri` is the canonical identity, for example `s3://media-output/jobs/<jobId>/operations/<operationId>/video-av1.mp4`. Storage credentials are never returned. List and detail responses do **not** embed a fresh signed URL.

Unknown artifact under that Job: **404** `ARTIFACT_NOT_FOUND`. An artifact that belongs to a different Job is also **404** `ARTIFACT_NOT_FOUND`. Unknown Job: **404** `JOB_NOT_FOUND`.

### Artifact access (presigned download URL)

Canonical Artifact identity stays `s3://bucket/key`. A download URL is a **temporary bearer capability**, generated only when requested.

This is a `POST` because it mints a new short-lived access URL; it is not reading immutable Artifact metadata (`GET` stays for that).

```bash
curl -sS -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" -X POST http://localhost:8080/jobs/<job-id>/artifacts/<artifact-id>/download-url
```

Example response:

```json
{
  "artifactId": "...",
  "url": "http://localhost:9000/media-output/jobs/.../thumbnail.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&...",
  "expiresAt": "2026-08-27T02:15:00Z",
  "contentType": "image/jpeg",
  "fileName": "thumbnail.jpg"
}
```

Then:

```bash
curl -L "<url from response>" -o /tmp/thumbnail.jpg
```

| Topic | Behavior |
| --- | --- |
| Default TTL | **15 minutes** (`ARTIFACT_URL_TTL`, min 1 minute, max 24 hours) |
| Existence | Java `HEAD`s the object before signing. Missing object → **404** `OBJECT_NOT_FOUND` (no fake URL) |
| MinIO down | **503** `OBJECT_STORE_UNAVAILABLE` |
| Invalid stored URI | **400** `ARTIFACT_URI_INVALID` |
| Filename | Taken from the object key (`thumbnail.jpg`, `audio.m4a`, `video-1080p.mp4`, `video-av1.mp4`). Signed GET does **not** force `Content-Disposition: attachment`, so browsers can preview images/video using the stored content type |
| Identity vs access | `GET /artifacts` stays stable `s3://` metadata. Signed URLs are not persisted and are not INFO-logged |

Owner-scoped: only the Account that owns the Job can mint a download URL. Cross-account requests return **404** `JOB_NOT_FOUND` and do not contact the object store. Once issued, the URL is a bearer capability until `expiresAt`; revoking the API key does not invalidate it.

The URL host comes from `OBJECT_STORE_ENDPOINT`. Java on the host against Compose MinIO should use `http://localhost:9000` (or the mapped host port). A URL signed for `http://minio:9000` works inside Docker but not in a host browser.

Local-only defaults (not production secrets):

```text
OBJECT_STORE_ENDPOINT=http://localhost:9000
OBJECT_STORE_REGION=us-east-1
OBJECT_STORE_ACCESS_KEY=minioadmin
OBJECT_STORE_SECRET_KEY=minioadmin
OBJECT_STORE_FORCE_PATH_STYLE=true
ARTIFACT_URL_TTL=15m
```

Path-style addressing is on by default so localhost MinIO does not need virtual-host DNS. SigV4 query strings include the **access key ID** (AWS protocol); they never include the secret key.

You can still copy objects with `mc` if you want the raw S3 identity:

```bash
docker run --rm --network host minio/mc \
  sh -c 'mc alias set local http://localhost:9000 minioadmin minioadmin && mc cp local/media-output/jobs/<job-id>/operations/<operation-id>/thumbnail.jpg /tmp/thumbnail.jpg'
```

If you mapped MinIO to another host port, change the alias URL and `OBJECT_STORE_ENDPOINT` to match.

### Submit a job

Submit a job. Execution is not started inside this request; the job is stored as `QUEUED`. The Go scheduler later assigns eligible operations.

```bash
curl -sS -X POST http://localhost:8080/jobs \
  -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" \
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

`priority` defaults to `NORMAL` when omitted. `deadline` is optional. Unknown jobs return **404**. Invalid bodies (missing `inputUri`, empty `operations`, unknown operation type, past deadline) return **400**.

### Cancel a job or operation

Cancellation is a `POST` because the job and its history remain. Both endpoints are **idempotent**.

```bash
curl -sS -X POST http://localhost:8080/jobs/<job-id>/cancel
curl -sS -X POST http://localhost:8080/jobs/<job-id>/operations/<operation-id>/cancel
```

Unknown jobs return **404** `JOB_NOT_FOUND`. An operation that does not belong to that job returns **404** `OPERATION_NOT_FOUND`.

| Current state | What cancellation does |
| --- | --- |
| `QUEUED` | becomes `CANCELLED` immediately; it is no longer schedulable |
| `ASSIGNED` but not started | becomes `CANCELLED`; the current `assignmentId` is cleared; a delayed RabbitMQ message cannot start it |
| `RUNNING` | becomes `CANCEL_REQUESTED`; the worker is told on lease renew; FFmpeg/ffprobe is killed; then attempt and operation become `CANCELLED` |
| already `CANCELLED` or `CANCEL_REQUESTED` | success with the current state |
| `COMPLETED` or `FAILED` | **409** — history is not rewritten; artifacts are not deleted |

Cancelling one operation does not cancel the others. A job whose requested work was explicitly cancelled becomes `CANCELLED` even if some operations already completed, because the requested job was not fully fulfilled. Any `FAILED` operation still makes the job `FAILED`.

Approximate cancellation latency for running FFmpeg is one **lease renew** (default `LEASE_RENEW_INTERVAL=10s`; workers also renew immediately after start). Temporary control-service failures do **not** fake a cancel. Successful artifacts from earlier completed operations are kept. A cancelled run must not persist a new Artifact. If the worker uploaded bytes before it learned about cancel, that object can remain in MinIO without an Artifact row (same orphan class as a crash before `complete`).

### Retry a failed operation

Retry is a `POST` because it starts a new execution cycle of the same Operation. It is **not** idempotent: `FAILED → QUEUED` is one cycle. A second retry while the operation is already `QUEUED` returns **409** `INVALID_OPERATION_STATE`.

```bash
curl -sS -X POST http://localhost:8080/jobs/<job-id>/operations/<operation-id>/retry
```

Example response:

```json
{
  "jobId": "...",
  "jobStatus": "QUEUED",
  "operationId": "...",
  "operationStatus": "QUEUED",
  "attemptCount": 1
}
```

`attemptCount` is how many `ExecutionAttempt` rows already exist (history). Retry does not create Attempt 2; the worker does that at `/start`.

| Current operation state | Retry |
| --- | --- |
| `FAILED` | → `QUEUED`; current failure fields are cleared; attempts stay in history |
| `COMPLETED` | **409** `INVALID_OPERATION_STATE` — artifacts are not overwritten |
| `CANCELLED` | **409** `INVALID_OPERATION_STATE` — retry is for execution failures, not intentional cancellation |
| `QUEUED` / `ASSIGNED` / `RUNNING` / `CANCEL_REQUESTED` | **409** `INVALID_OPERATION_STATE` |

Same `inputUri`. The Go scheduler places the work again with the **current** FIFO + worker policy. The worker may differ from Attempt 1. Successful sibling operations and their artifacts are kept. The parent Job leaves `FAILED` and becomes `QUEUED`, `ASSIGNED`, or `RUNNING` according to remaining operations.

Optional whole-job retry queues every **FAILED** operation and leaves `COMPLETED` / `CANCELLED` operations untouched. If none are `FAILED`:

```text
409 NOTHING_TO_RETRY
```

Retry is **explicit**. The platform does not automatically retry FFmpeg/ffprobe application failures. `MAX_EXECUTION_ATTEMPTS` only stops automatic **lease-recovery** requeues; a user can still retry a FAILED operation afterward.

`inputUri` is stored as a URI string. The public API does **not** contact S3 or verify that the object exists. Dispatch executes `file://` and `s3://` for `METADATA`, `THUMBNAIL`, `AUDIO_EXTRACTION`, `TRANSCODE_1080P`, and `H264_TO_AV1`. A job is not `COMPLETED` while any of those remain queued or running.

Supported operation types for submission:

```text
METADATA
    inspect media

THUMBNAIL
    create preview image

AUDIO_EXTRACTION
    extract AAC/M4A audio

TRANSCODE_1080P
    create H.264 MP4 capped at 1080p

H264_TO_AV1
    convert H.264 video to AV1 while preserving resolution
```

`TRANSCODE_4K_TO_1080P` is retired. New submissions return **400** `INVALID_ENUM_VALUE`. Use `TRANSCODE_1080P` for 4K (or any larger) sources that need a 1080p-or-lower H.264 MP4.

**Executable operations:** `METADATA`, `THUMBNAIL`, `AUDIO_EXTRACTION`, `TRANSCODE_1080P`, `H264_TO_AV1`.

The scheduler places executable types onto a specific worker; registration and capability matching prevent dispatch to a worker that did not advertise the type. A worker without a usable AV1 encoder does not advertise `H264_TO_AV1`; that work stays `QUEUED` until a capable worker exists.

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

Scheduling unit is **Operation**, not whole Job. FIFO means the oldest **current queue entry** across jobs:

```text
queuedAt ASC, operationOrder ASC, id ASC
```

`queuedAt` is the time the operation entered (or re-entered) the scheduling queue:

- initial submit: `queuedAt = createdAt`
- explicit user retry: `queuedAt = retry time`
- assignment-timeout recovery: `queuedAt = recovery time`
- lease-interruption requeue: `queuedAt = recovery time`

Logical `createdAt` is unchanged. Retried or recovered work therefore goes to the **back** of the FIFO queue rather than jumping ahead of newer submissions. Job `priority` and `deadline` are persisted but **intentionally ignored** so FIFO stays a pure baseline. This phase does not skip an older unschedulable operation to run a younger one; if the oldest queued `THUMBNAIL` has no eligible worker, it stays `QUEUED` and the scheduler logs `no_eligible_worker` (no hot loop — it sleeps `SCHEDULER_POLL_INTERVAL`). Worker registration/recovery may make it schedulable later. The operation is not failed.

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

Round Robin: `METADATA`, `THUMBNAIL`, `AUDIO_EXTRACTION`, `TRANSCODE_1080P`, and `H264_TO_AV1` rotate independently using committed RR history. `UNAVAILABLE` workers leave the current rotation and may rejoin later; there is no downtime-compensation credit. Round Robin ignores current executing work.

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

Generate a tiny local clip (do not commit large binaries). Video-only is enough for METADATA/THUMBNAIL. AUDIO_EXTRACTION needs an audio stream. TRANSCODE_1080P needs video; audio is optional. H264_TO_AV1 needs an H.264 video stream; audio is optional:

```bash
ffmpeg -y -f lavfi -i testsrc=duration=2:size=320x240:rate=30 -pix_fmt yuv420p /tmp/sample.mp4
ffmpeg -y -f lavfi -i testsrc=duration=2:size=320x240:rate=30 \
  -f lavfi -i sine=frequency=440:duration=2 \
  -pix_fmt yuv420p -c:v libx264 -c:a aac -shortest /tmp/audio-sample.mp4
ffmpeg -y -f lavfi -i testsrc=duration=3:size=2560x1440:rate=24 \
  -f lavfi -i sine=frequency=440:duration=3 \
  -pix_fmt yuv420p -c:v libx264 -preset ultrafast -c:a aac -shortest /tmp/transcode-source.mp4
```

Upload it to MinIO. With the AWS CLI:

```bash
AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin \
  aws --endpoint-url http://localhost:9000 s3 cp /tmp/sample.mp4 s3://media-input/sample.mp4
AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin \
  aws --endpoint-url http://localhost:9000 s3 cp /tmp/audio-sample.mp4 s3://media-input/audio-sample.mp4
AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin \
  aws --endpoint-url http://localhost:9000 s3 cp /tmp/transcode-source.mp4 s3://media-input/transcode-source.mp4
```

Or with the MinIO client in Docker:

```bash
docker run --rm --network host \
  -v /tmp/sample.mp4:/sample.mp4 \
  -v /tmp/audio-sample.mp4:/audio-sample.mp4 \
  -v /tmp/transcode-source.mp4:/transcode-source.mp4 minio/mc \
  sh -c 'mc alias set local http://localhost:9000 minioadmin minioadmin && mc cp /sample.mp4 local/media-input/sample.mp4 && mc cp /audio-sample.mp4 local/media-input/audio-sample.mp4 && mc cp /transcode-source.mp4 local/media-input/transcode-source.mp4'
```

Canonical object: `s3://media-input/sample.mp4`. Audio sample: `s3://media-input/audio-sample.mp4`. Transcode sample: `s3://media-input/transcode-source.mp4`.

Start PostgreSQL, MinIO, RabbitMQ, and the control service as above, then submit:

```bash
curl -sS -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "inputUri": "s3://media-input/audio-sample.mp4",
    "operations": [
      {"type": "METADATA"},
      {"type": "THUMBNAIL"},
      {"type": "AUDIO_EXTRACTION"},
      {"type": "TRANSCODE_1080P"},
      {"type": "H264_TO_AV1"}
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

Transcode:

```bash
curl -sS -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "inputUri": "s3://media-input/transcode-source.mp4",
    "operations": [{"type": "TRANSCODE_1080P"}]
  }'
```

H.264 to AV1 (same H.264 sample; resolution is preserved, not capped at 1080p):

```bash
curl -sS -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "inputUri": "s3://media-input/audio-sample.mp4",
    "operations": [{"type": "H264_TO_AV1"}]
  }'
```

The job is `QUEUED` until the scheduler assigns an operation (`ASSIGNED`), a worker starts one (`RUNNING` + `ExecutionAttempt`), and results are persisted (`COMPLETED` / `FAILED`). Interrupted infrastructure failures requeue the operation; the scheduler places it again. Attempt history is retained.

`WORKER_ID` is **required** (stable identity such as `worker-a`). The worker probes local executables and machine info, **declares its durable RabbitMQ queue**, registers, starts a heartbeat loop, and only then consumes `media.worker.{WORKER_ID}`. After `start` succeeds it also runs a **lease-renewal loop** for that attempt until complete/fail. `supportedOperations` means the worker has an implemented executor **and** the required local binary is available (`METADATA` needs ffprobe; `THUMBNAIL` and `AUDIO_EXTRACTION` need FFmpeg; `TRANSCODE_1080P` needs FFmpeg with the `libx264` encoder; `H264_TO_AV1` needs FFmpeg with `libsvtav1` or `libaom-av1`). Optional `SUPPORTED_OPERATIONS` may **restrict** that set; it cannot add unimplemented types. If a requested operation's executable or encoder is missing, startup fails: the worker does not register, does not heartbeat, and does not consume. The default (unrestricted) worker set **skips** `H264_TO_AV1` when no usable AV1 encoder is present so H.264-only machines can still run the other operations. Metadata-only workers (`SUPPORTED_OPERATIONS=METADATA`) do not require FFmpeg; encoder `supportedCodecs` stay empty in that case.

```bash
cd worker

WORKER_ID=worker-a \
WORKER_SERVICE_TOKEN="$WORKER_A_TOKEN" \
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
WORKER_SERVICE_TOKEN="$WORKER_B_TOKEN" \
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

Those MinIO and RabbitMQ keys are local development defaults. `PREFETCH` defaults to `1`. `FFPROBE_PATH` defaults to `ffprobe`. `FFMPEG_PATH` defaults to `ffmpeg`. `WORKER_HOSTNAME` overrides `os.Hostname()` when set. `HEARTBEAT_INTERVAL` defaults to `5s` (Go duration, for example `5s` or `500ms`). `LEASE_RENEW_INTERVAL` defaults to `10s` and should stay below half of `OPERATION_LEASE_DURATION` (control-service default `30s`). Invalid or non-positive values fail startup. `EXECUTION_TIMEOUT` is optional; empty, `0`, or `0s` means no extra wall-clock cap (the previous accidental two-minute worker context is gone). Running work is bounded by user cancellation, lease ownership, and worker shutdown. Stop a worker with SIGINT/SIGTERM: in-flight unacked messages are requeued by RabbitMQ; missed heartbeats eventually mark the worker `UNAVAILABLE`; an unrenewed lease plus `UNAVAILABLE` lets the control plane interrupt the attempt and requeue the operation **unless** the user already requested cancellation, in which case the operation becomes `CANCELLED` and is not rescheduled. There is no explicit relinquish endpoint in this phase.

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

Observe worker logs for `event=registered`, then `event=consuming queue=media.worker.{id}`, `event=received`, `event=execution_start`, `event=execution_completed` / `event=execution_failure`, and `event=ack` / `event=nack_requeue`. A v3 assignment whose `workerId` does not match is dropped (`event=worker_id_mismatch`). A stale recovered assignment is dropped (`event=stale_assignment_start_rejected`) and does not run media. An assignment this worker did not advertise is not executed; the message is dead-lettered (`event=capability_mismatch`). Scheduler logs include `event=assigned` and `event=no_eligible_worker`.

`METADATA` stores parsed probe JSON on the operation. `THUMBNAIL` extracts one JPEG frame (seek ~1s, falling back to the first frame on short clips) and uploads:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/thumbnail.jpg
```

`AUDIO_EXTRACTION` runs FFmpeg to produce AAC in an M4A container (`-vn -map 0:a -c:a aac -b:a 192k`). Content type is `audio/mp4`. Canonical object:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/audio.m4a
```

Inputs with no audio stream fail the operation (`FAILED`) with reason `input has no audio stream`. Empty output is rejected. Codec/bitrate are not configurable in this phase.

`TRANSCODE_1080P` produces a broadly compatible H.264 MP4, at most 1920×1080, without upscaling smaller inputs. Aspect ratio is preserved. Audio is re-encoded to AAC when present; video-only inputs succeed as video-only MP4. Inputs with no video stream fail (`input has no video stream`). Content type is `video/mp4`. Canonical object:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/video-1080p.mp4
```

This is a 1080p-or-lower compatibility transcode, not a quality or bitrate guarantee. CRF 23 / medium preset are fixed defaults. 4K and other larger sources are accepted and downscaled; there is no separate `TRANSCODE_4K_TO_1080P` operation.

`H264_TO_AV1` converts an H.264 video stream to AV1 in MP4 without changing resolution. The primary video codec must be H.264; other codecs fail (`input video codec is not h264`). Audio is re-encoded to AAC when present; video-only inputs succeed as video-only MP4. Inputs with no video stream fail (`input has no video stream`). The worker uses `libsvtav1` when FFmpeg provides it, otherwise `libaom-av1`. It does not advertise the operation unless one of those encoders is present. AV1 output is not guaranteed to be smaller or faster than the H.264 source. Content type is `video/mp4`. Canonical object:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/video-av1.mp4
```

`GET /jobs/{id}/artifacts` returns type (`THUMBNAIL`, `AUDIO`, `TRANSCODE_1080P`, or `H264_TO_AV1`), object URI, content type, size, and SHA-256 checksum. Media bytes stay in MinIO.

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
  -H "Authorization: Bearer $MEDIA_PLATFORM_API_KEY" \
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
WORKER_SERVICE_TOKEN="$WORKER_A_TOKEN" \
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
WORKER_SERVICE_TOKEN="$WORKER_B_TOKEN" \
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

`TRANSCODE_1080P` produces a broadly compatible H.264 MP4, at most 1920×1080, without upscaling smaller inputs. Aspect ratio is preserved. Audio is re-encoded to AAC when present; video-only inputs succeed as video-only MP4. Inputs with no video stream fail (`input has no video stream`). Content type is `video/mp4`. Canonical object:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/video-1080p.mp4
```

This is a 1080p-or-lower compatibility transcode, not a quality or bitrate guarantee. CRF 23 / medium preset are fixed defaults. 4K and other larger sources are accepted and downscaled; there is no separate `TRANSCODE_4K_TO_1080P` operation.

`H264_TO_AV1` converts an H.264 video stream to AV1 in MP4 without changing resolution. The primary video codec must be H.264; other codecs fail (`input video codec is not h264`). Audio is re-encoded to AAC when present; video-only inputs succeed as video-only MP4. Inputs with no video stream fail (`input has no video stream`). The worker uses `libsvtav1` when FFmpeg provides it, otherwise `libaom-av1`. It does not advertise the operation unless one of those encoders is present. AV1 output is not guaranteed to be smaller or faster than the H.264 source. Content type is `video/mp4`. Canonical object:

```text
s3://media-output/jobs/<jobId>/operations/<operationId>/video-av1.mp4
```

`GET /jobs/{id}/artifacts` returns type (`THUMBNAIL`, `AUDIO`, `TRANSCODE_1080P`, or `H264_TO_AV1`), object URI, content type, size, and SHA-256 checksum. Media bytes stay in MinIO.

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

Internal `POST /internal/workers/register` and `POST /internal/workers/{workerId}/heartbeat` require a worker service token bound to that worker id. Account API keys are rejected.

`file://` inputs still work for both operations. Thumbnail output is always stored in the output bucket.

A missing object (`s3://media-input/does-not-exist.mp4`) becomes operation `FAILED` and job `FAILED`, with a persisted `failureReason` that does not include credentials.

Internal worker endpoints (`POST /internal/operations/{id}/start`, `.../attempts/{attemptId}/renew`, `.../complete`, `.../fail`, `.../cancelled`) require the owning worker's token. Authenticated worker identity must match the assignment/attempt owner. Attempt UUID validation remains; internal auth does not replace it.

`POST /internal/operations/claim` still exists but is **disabled by default** (`drive.dispatch.http-claim-enabled=false`) so it does not compete with the scheduler. Existing tests turn it on. When enabled, claim still requires a worker token bound to `workerId`. Do not run poll-based workers against a scheduler-enabled control service.

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

Keep `LEASE_RENEW_INTERVAL` less than half of `OPERATION_LEASE_DURATION`. Transient renew failures are logged and retried on the next interval; they do not kill media work. The renew response includes `cancelRequested`. When it is true, the worker cancels the execution context (FFmpeg/ffprobe exits), does not call `complete`, and acknowledges `POST /internal/operations/{operationId}/attempts/{attemptId}/cancelled`. If renewals stop and the worker is later `UNAVAILABLE`, the control plane may reclaim a `RUNNING` attempt onto `QUEUED`. A `CANCEL_REQUESTED` attempt whose lease expires becomes `CANCELLED` instead of being requeued. A late `complete`/`fail` from that attempt is then `409 STALE_EXECUTION_ATTEMPT`.

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

After `MAX_EXECUTION_ATTEMPTS` infrastructure interruptions, the operation becomes `FAILED` with reason `maximum execution attempts exceeded`. Real FFmpeg/ffprobe errors still fail the attempt immediately and are **not** auto-retried. An explicit `POST .../retry` can queue that FAILED operation again; automatic lease recovery will not keep looping forever.

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

Thumbnail object keys stay `s3://media-output/jobs/<jobId>/operations/<operationId>/thumbnail.jpg`. Audio keys stay `s3://media-output/jobs/<jobId>/operations/<operationId>/audio.m4a`. Transcode keys stay `s3://media-output/jobs/<jobId>/operations/<operationId>/video-1080p.mp4`. AV1 keys stay `s3://media-output/jobs/<jobId>/operations/<operationId>/video-av1.mp4`. A retry may overwrite the same key. If a worker uploads then dies before `complete` persists, the object can exist without an Artifact row. That orphan is **not** garbage-collected in this phase. Execution is **at-least-once**, not exactly-once.

`GET /jobs/{jobId}/operations/{operationId}/attempts` is a read-only history API (no lease internals).

### Phase 5F limitations

- TRANSCODE_4K_TO_1080P is retired; use TRANSCODE_1080P for 1080p H.264 output including 4K sources
- H264_TO_AV1 requires an H.264 video stream and a software AV1 encoder (`libsvtav1` or `libaom-av1`)
- H264_TO_AV1 is a fixed codec conversion (resolution preserved; AAC audio when present); no codec/bitrate API
- AV1 output is not a size or speed guarantee versus the H.264 source
- TRANSCODE_1080P is a fixed H.264/AAC compatibility encode (CRF 23, medium); no codec/bitrate API
- AUDIO_EXTRACTION is a fixed AAC/M4A extract; no codec/bitrate API
- only FIFO operation ordering; no SJF, EDF, or adaptive scoring
- worker placement is LEXICOGRAPHIC, ROUND_ROBIN, or LEAST_LOADED; not weighted or adaptive
- Least Loaded counts RUNNING attempts only; ASSIGNED-not-started work is not reserved
- Least Loaded and Round Robin are not throughput or latency claims
- FIFO ignores persisted priority and deadline
- no runtime estimator, queue-wait prediction, or utilization telemetry
- no CPU/memory scoring even though static cores/memory are registered
- operator OpenTelemetry (Jaeger/Prometheus/Grafana) is in Phase 6A; the product timeline is `GET /jobs/{id}/timeline` from persisted state, not from traces
- timeline is not paginated, filtered, or streamed (no SSE/WebSocket); a future phase may need pagination if retry history grows large
- no benchmark framework
- worker queues are not deleted when a worker becomes `UNAVAILABLE`
- the legacy Java enqueue path remains for tests (`drive.dispatch.scheduling-enabled`); keep it off in production
- running cancellation is observed on lease renew (about 10s by default), not a dedicated cancel broker
- orphan MinIO objects from a cancelled upload are not garbage-collected
- no automatic retries or retry backoff; only explicit `POST .../retry` of FAILED operations
- presigned download URLs are time-limited bearer capabilities; already-issued URLs are not revoked when an API key is revoked
- internal credentials are environment-managed; rotation is a restart with a new secret, not automated
- local HTTP is development-only; the Compose product path terminates TLS at Caddy. Internal Compose traffic (scheduler/workers → Java) is HTTP, not mTLS
- no mTLS, service mesh, SPIFFE, OAuth, JWT, Kubernetes, Helm, Terraform, or secret-manager integration
- no Docker registry publish and no AWS/GCP/Azure deploy in this phase
- `docker compose up --scale` cannot mint per-worker tokens; add named worker services
- if an old RabbitMQ volume was created with a different image/user, a `.erlang.cookie` permission error may require `docker compose down` and removing that volume (or `down -v` as a full reset)
- no user RBAC, organizations/teams, audit log, or rate limiting
- `POST /accounts` can be disabled but is still a bootstrap, not production-grade identity administration
- no frontend, passwords, JWT, or OAuth
- no upload/presigned PUT APIs

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

Pushes to non-`main` branches run **Branch CI**. Pull requests to `main` and pushes/merges to `main` run **PR / Main CI**. Java CI executes `./mvnw clean test` from `Server/drive`. The **Go tests** job runs `go vet` / `go test` in `worker/` and `scheduler/`. A **Compose config** job runs `docker compose config`. The existing required-check names **Java tests** and **Go tests** are unchanged.

These workflows are a **build/test gate**. They do not deploy anything. Deployment will be designed later.

See [docs/github-workflow.md](docs/github-workflow.md) for the full flow, the local CI equivalent, and the recommended GitHub settings to protect `main` (requiring a PR, requiring **Java tests**, blocking force pushes). Creating the YAML files does not enable those settings by itself.

## What comes later

The smallest next **product** milestone is a live Job view (polling or later SSE) on top of this timeline API, a hardened production secret/TLS story, or cloud-hosted deploy. This phase is local Compose only — not Kubernetes, Terraform, or a registry publish. A scheduling benchmark harness, SJF, EDF, runtime estimation, alerting, and SLO frameworks remain later still.
