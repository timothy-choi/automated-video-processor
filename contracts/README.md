# Operation assignment contract

## v2 (Phase 4A)

Canonical schema: [`operation-assignment.v2.schema.json`](operation-assignment.v2.schema.json)

Published to RabbitMQ as persistent JSON after the Go scheduler commits a placement through the Java control service. Java publisher and Go workers must keep this envelope aligned.

```json
{
  "schemaVersion": 2,
  "operationId": "11111111-1111-1111-1111-111111111111",
  "jobId": "22222222-2222-2222-2222-222222222222",
  "type": "THUMBNAIL",
  "inputUri": "s3://media-input/sample.mp4",
  "workerId": "worker-a",
  "scheduledAt": "2026-08-25T18:00:00Z",
  "policy": "FIFO"
}
```

**v2 does not include `attemptId`.** Ownership is still created when the targeted worker calls `POST /internal/operations/{id}/start` with its `workerId`. Extra JSON fields must still be ignored. A worker that receives a v2 assignment whose `workerId` does not match must not execute it; it dead-letters the message.

## v1 (legacy)

Canonical schema: [`operation-assignment.v1.schema.json`](operation-assignment.v1.schema.json)

Used only by the legacy Java enqueue path (tests / `drive.dispatch.scheduling-enabled=true`), which publishes to the shared competing-consumer queue. Workers still parse v1. Production Phase 4A uses v2.

## RabbitMQ topology (Phase 4A)

| Name | Value |
| --- | --- |
| Exchange | `media.operations` (direct, durable) |
| Worker queue | `media.worker.{workerId}` (durable, declared by the worker) |
| Routing key | `worker.{workerId}` |
| Dead-letter exchange | `media.operations.dlx` (direct, durable) |
| Dead-letter queue | `media.operations.execute.dlq` |
| Dead-letter routing key | `operation.execute.dead` |

The Java AMQP configuration still declares the legacy shared queue `media.operations.execute` / `operation.execute` for tests. Default production publish uses the per-row outbox routing key.

Messages are persistent. Consumers use manual ACK and `prefetch=1`. Malformed or non-requeued rejects go to the DLQ. RabbitMQ is delivery infrastructure; the Go scheduler owns placement.
