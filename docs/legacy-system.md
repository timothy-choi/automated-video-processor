# Legacy system inventory

This document records what existed in the original Automated Video Processor repository when Phase 1 began, and what was done with each subsystem.

The future project identity is **not** “automate Google Slides and upload videos to YouTube.” It is an **Adaptive Distributed Media Processing Platform**: schedule real heterogeneous media workloads across distributed workers and measure the results.

Phase 1 does **not** rewrite retired subsystems so they compile. Code that is unused by the canonical Maven application is left in place unless it blocked the active build.

Classifications:

- **KEEP** — useful and sufficiently aligned
- **KEEP + MODIFY** — concept and much of the implementation are useful, but changes were needed
- **REFACTOR** — belongs in the new system, but needs meaningful restructuring
- **REPLACE** — concept is relevant; this implementation is unsuitable for the eventual architecture
- **RETIRE** — belongs to the old product direction and does not support the scheduling platform

---

## Canonical application: `Server/drive`

**What it did.** This was the only Maven/Spring Boot project in the repository. The entrypoint is `com.example.drive.DriveApplication`. Most of the original product logic lived *outside* this source tree under `Server/API`, `Server/AWS`, `Server/RabbitMQ`, and related directories, so it was never compiled by Maven.

**Did it work?** The Spring Boot shell could be assembled, but the compiled tree also contained DynamoDB configuration that required `aws.accessKey` / `aws.secretKey` at startup, plus `DynamoDBStart.java`, which does not compile (`import Server.API.Templates`). There was no health endpoint, no real application configuration, and the POM pulled in unused/conflicting dependencies (GraphQL, WebFlux, two DynamoDB Spring Data libraries, JavaCV, Google APIs, RabbitMQ, an obsolete Spring Data release train).

**Active in Phase 1?** Yes. This directory remains the canonical Java control-service application. It was not renamed to `control-service/` in order to keep the first migration small.

**Classification:** KEEP + MODIFY

**Rationale.** This is the actual Spring Boot application. Phase 1 kept its location, entrypoint class name (`DriveApplication`), Maven wrapper, and Java 21 baseline. Spring Boot was updated from 3.1.5 (EOL) to 4.1.1 so the control service sits on a currently supported combination. Unused and conflicting dependencies were removed; DynamoDB classes were isolated from the compile/boot path; a health endpoint and tests were added.

---

## DynamoDB setup (`Server/drive/legacy`)

**What it did.** `DynamoDBSetup` created an AWS SDK v1 DynamoDB client from Spring properties `aws.accessKey` and `aws.secretKey`, with a hardcoded `US_EAST_1` region. `DynamoDBStart` attempted to create a DynamoDB table for the Templates entity on startup.

**Did it work?** `DynamoDBSetup` is structurally a Spring `@Configuration` class, but booting required AWS credentials. `DynamoDBStart` does not compile (invalid import, unused Spring annotations, incomplete wiring). The POM also declared two incompatible `spring-data-dynamodb` libraries plus Spring Data Lovelace-SR16, which is not a valid pairing with Spring Boot 3/4.

**Active in Phase 1?** No. Both files were moved from `src/main/java` to `Server/drive/legacy/` so they are preserved but not compiled.

**Classification:** REPLACE (state-store *concept*) / RETIRE (this DynamoDB bootstrap)

**Rationale.** A control-plane state store belongs in later phases and should be chosen deliberately. These classes would have forced AWS credentials just to start the control service. They were isolated rather than repaired.

---

## Accounts (`Server/API/Accounts`)

**What it did.** Intended MongoDB-backed user accounts (`@Document(collection = "accounts")`) with create/read/update/delete endpoints, video counts, and template associations.

**Did it work?** No. Inspection shows it does not compile: invalid type `Int`, missing imports and annotations (`@Autowired`, `@GetMapping`, `ResponseEntity`), broken constructor usage (`new ResponseEntity(...)`), mixed `javax.persistence` with Spring Data MongoDB, inconsistent packages (`api.accounts` vs `api.auth`), and `throw new Exception` from methods that do not declare checked exceptions. It was never on the Maven source path.

**Active in Phase 1?** No.

**Classification:** RETIRE

**Rationale.** User-account CRUD is an old product concern. The future control service models jobs, workers, and execution history, not this account model. Repairing it would not advance the scheduling platform.

---

## Templates / Google Slides (`Server/API/Templates`)

**What it did.** Intended DynamoDB persistence for slide templates plus a large REST controller that called Google Slides (`TemplateOperations`) to create presentations, slides, text, shapes, and images. Controllers often re-fetched their own data through `WebClientConfig`.

**Did it work?** No. Widespread non-Java types (`Int`, `bool`, `string`), undefined variables (`requestId`, `TemplateName`, `text` vs `Text`), invalid DynamoDB annotations (`@DynamoDHashKey`), JavaFX `Pair` usage, and Google Slides API calls that do not match the real client types. Not on the Maven source path.

**Active in Phase 1?** No.

**Classification:** RETIRE

**Rationale.** Google Slides template editing is the old product’s composition UI, not the new platform’s media-job control plane.

---

## VideoAccounts (`Server/API/VideoAccounts`)

**What it did.** Intended per-user video-account records (DynamoDB) plus endpoints to download from S3, authorize Google users, and upload results to YouTube or Google Drive. Also included a RabbitMQ enqueue endpoint.

**Did it work?** No. Same class of compilation failures (`bool`, undefined identifiers, incorrect SDK types, `WebClient` used as a static self-call). Repository lives in package `api.videoProcessing` while the entity lives in `api.videoAccounts`. Not on the Maven source path.

**Active in Phase 1?** No.

**Classification:** RETIRE

**Rationale.** “Video accounts” and publishing destinations are old product features. Object storage remains relevant later, but this account wrapper is not the future job API.

---

## VideoProcessing (`Server/API/VideoProcessing`)

**What it did.** Intended the old product’s core workflow: bind a Google Slides template to a processing instance, convert slides to images, partition slides, import videos to S3, order clips, apply animations, compose partition videos, then concatenate a final video. Persistence was modeled as DynamoDB.

**Did it work?** No. The controller and entity do not compile (`bool`, `Int`, fluent setters that return `void`, `javafx.util.Pair`, mixed AWS SDK v1/v2 types, `Producer.sendMessage` with no import, machine-specific `~/Downloads` paths). Not on the Maven source path.

**Active in Phase 1?** No.

**Classification:** RETIRE

**Rationale.** This is in-process, synchronous, Slides-driven video generation inside the Java API. The future control service must not run FFmpeg/JavaCV itself. Keeping this as the active processing path would fight the target architecture.

---

## JavaCV / FFmpeg helpers

**What they did.**

- `VideoProcessor` concatenates local video files with JavaCV `FFmpegFrameGrabber` / `FFmpegFrameRecorder`, writing to `~/Downloads`.
- `SlideVideoConverter` builds MP4s from slide images with fade/slide/zoom animation sketches.
- `ImageSlide` intended to download Google Slides thumbnails as PNGs.

**Did they work?** They do not compile as written (checked `Exception` thrown from methods that do not declare it, missing imports, `Int`/`bool`, undefined identifiers such as `SlideDuration` and `animation`). The JavaCV dependency was listed in `Server/drive/pom.xml` but these classes were outside the Maven source tree, so Maven never compiled them.

**Active in Phase 1?** No. The JavaCV dependency was removed from the active POM.

**Classification:** REPLACE

**Rationale.** Real FFmpeg workloads are central to later phases, but they belong in Go workers, not in the Java control service. This JavaCV-in-API prototype is useful history, not the worker implementation.

---

## AWS S3 helpers (`Server/AWS`)

**What they did.** `AWSSetup` read `accessKey` / `secretKey` from environment variables. `AWSHelper` mixed AWS SDK v1 (`AmazonS3ClientBuilder`) and v2 (`GetObjectRequest.builder()`, multipart upload) to create buckets and put/get/delete objects.

**Did it work?** The helper does not compile (`bool`, missing types, v1/v2 client mismatch, `s3Client` used without being assigned in the v2 methods). Credentials were at least read from the environment rather than hard-coded in source. Not on the Maven source path.

**Active in Phase 1?** No.

**Classification:** REPLACE

**Rationale.** Object storage is required in later phases. This helper is not a sound S3 client and must not be required to boot the control service.

---

## RabbitMQ prototype (`Server/RabbitMQ`)

**What it did.** A Java AMQP producer declared a direct exchange `processing_routes` and three durable queues (`partition_processing_queue`, `video_processing_queue`, `youtube_upload_queue`) on `localhost`. A consumer auto-acked those queues and attempted to forward payloads over a WebSocket (`/NotifyRequest`). Host was hard-coded to `localhost`; no password appeared in source.

**Did it work?** It does not compile (`bool`, invalid `Consumer` type usage, `new URI("")`, instance methods called as static). It was an experiment for the old Slides/YouTube workflow, not a worker dispatch bus. The `amqp-client` dependency sat unused in the Maven POM because these classes were outside the source tree.

**Active in Phase 1?** No. The AMQP dependency was removed from the active POM.

**Classification:** REPLACE

**Rationale.** RabbitMQ is the planned dispatch mechanism for later phases. This prototype should not be repaired into the Phase 1 control service; a later redesign will own topology, acknowledgments, retries, and DLQ.

---

## Google Drive (`Server/GoogleDrive`)

**What it did.** Intended Drive API upload of an MP4 using user OAuth credentials.

**Did it work?** No. It does not compile (invalid `Builder`, `bool`/`string`, incomplete uploader wiring). Not on the Maven source path.

**Active in Phase 1?** No.

**Classification:** RETIRE

**Rationale.** Drive upload is an old product distribution feature, not a control-plane or worker concern.

---

## YouTube (`Server/Youtube`)

**What it did.** Intended YouTube Data API video create/upload/get, including a resumable-upload retry loop.

**Did it work?** No. It does not compile (wrong types, typo `GoogleJsonResponseExceptionle`, `Youtube` vs `YouTube`). Not on the Maven source path.

**Active in Phase 1?** No.

**Classification:** RETIRE

**Rationale.** Publishing to YouTube is outside the scheduling platform’s core problem.

---

## GCP OAuth helpers (`Server/GCP`)

**What it did.** Intended Google OAuth2 authorization-code flow for Drive/YouTube/Slides, loading client secrets from a classpath resource.

**Did it work?** No. Client-secrets filename and redirect URI are empty strings; the code does not compile (missing types, incorrect builder usage). No client secret values are embedded in source. Not on the Maven source path.

**Active in Phase 1?** No.

**Classification:** RETIRE

**Rationale.** Google user OAuth exists only to support retired Drive/YouTube/Slides features.

---

## WebClient configuration (`Server/API/WebClientConfig`)

**What it did.** A Spring `@Configuration` that exposed a static `WebClient` bean with an empty `baseUrl`. Controllers used it to HTTP-call other controllers in the same process.

**Did it work?** It does not compile (missing `HttpHeaders` / `MediaType` imports). Not on the Maven source path.

**Active in Phase 1?** No.

**Classification:** RETIRE

**Rationale.** In-process HTTP self-calls are not a persistence or service layer. Future control-service code should use ordinary Spring beans.

---

## Go / Kafka notifications (`Server/API/Notifications`)

**What it did.** An unfinished Go module (`github.com/timothy-choi/automated-video-processor`) using Gin and IBM Sarama. Producer and consumer mains target a `notifications` Kafka topic. Broker address and HTTP ports are empty strings.

**Did it work?** The Go sources do not build as written (undefined identifiers, package path mismatches, syntax issues such as `notifications.Notification({`). This is a notification experiment, not a scheduler or FFmpeg worker.

**Active in Phase 1?** No.

**Classification:** RETIRE

**Rationale.** Later Go code should be a scheduler and FFmpeg workers. Kafka is explicitly out of scope unless a concrete need appears; RabbitMQ is the planned dispatch mechanism. This experiment is preserved as history only.

---

## Persistence note

The original design mixed **MongoDB** (Accounts) and **DynamoDB** (Templates, VideoAccounts, VideoProcessing) and never connected either to a running, compiling application. Phase 1’s control service had no datastore.

**Phase 2A** chose **PostgreSQL** as the control-plane state store, with Flyway owning the schema. Legacy DynamoDB/MongoDB code was not restored. The active tables are `jobs` and `operations` only.

---

## What Phase 1 changed in the tree

| Path | Action |
| --- | --- |
| `Server/drive` | Canonical app; POM, config, health endpoint, tests |
| `Server/drive/legacy/DynamoDBSetup.java` | Moved out of `src/main/java` (git rename) |
| `Server/drive/legacy/DynamoDBStart.java` | Moved out of `src/main/java` (git rename) |
| `Server/API/**` | Left in place, not compiled |
| `Server/AWS`, `Server/GCP`, `Server/GoogleDrive`, `Server/RabbitMQ`, `Server/Youtube` | Left in place, not compiled |
| `Server/API/Notifications` | Left in place, not compiled |

No scheduler, worker registry, RabbitMQ redesign, OpenTelemetry, or FFmpeg worker was added in Phase 1.
