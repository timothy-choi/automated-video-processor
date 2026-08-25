# Adaptive Distributed Media Processing Platform

This repository is evolving from the original **Automated Video Processor** into a new portfolio project:

**Adaptive Distributed Media Processing Platform** — a distributed system that will eventually schedule heterogeneous media-processing jobs across workers based on workload characteristics, worker resources, load, priority, and deadlines.

That later architecture (Go scheduler, RabbitMQ, FFmpeg workers, object storage, OpenTelemetry) is **not implemented yet**. This repository is currently at **Phase 1**.

## Current status: Phase 1 baseline

Phase 1 stabilizes the existing project into a trustworthy Java/Spring Boot control-service starting point.

The canonical application is the Maven/Spring Boot project at:

```text
Server/drive
```

It currently:

- builds
- runs automated tests
- starts locally without AWS, RabbitMQ, Google APIs, or other external infrastructure
- exposes `GET /health`

It does **not** yet submit jobs, schedule work, run FFmpeg, or talk to a message broker.

## Build

From `Server/drive`:

```bash
./mvnw clean test
```

Requires **Java 21+**. The Maven wrapper (`./mvnw`) is preferred over a system Maven install.

Phase 1 stack: **Java 21**, **Spring Boot 4.1.1**, **Maven**. The Maven `artifactId` remains `drive` so the existing project location is unchanged.

## Run tests

```bash
cd Server/drive
./mvnw test
```

## Start the application

```bash
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

## What comes later

Distributed execution, scheduling policies, worker registration, RabbitMQ dispatch, FFmpeg workers, and observability belong to later phases. Do not assume those features exist because the long-term design mentions them.
