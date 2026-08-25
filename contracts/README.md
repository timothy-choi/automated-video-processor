# Operation assignment contract (v1)

Canonical schema: [`operation-assignment.v1.schema.json`](operation-assignment.v1.schema.json)

Published to RabbitMQ as persistent JSON. Java dispatcher and Go workers must keep this envelope aligned.

```json
{
  "schemaVersion": 1,
  "operationId": "11111111-1111-1111-1111-111111111111",
  "jobId": "22222222-2222-2222-2222-222222222222",
  "type": "METADATA",
  "inputUri": "s3://media-input/sample.mp4",
  "dispatchedAt": "2026-08-25T02:00:00Z"
}
```

`type` is typically `METADATA` or `THUMBNAIL` today. Unknown extra fields must be ignored so later `attemptId` / `workerId` / `leaseToken` / trace context can be added without a breaking change. A worker that receives a type it did not register (for example `TRANSCODE_1080P`) must not execute it; it dead-letters the message.

## RabbitMQ topology (Phase 3A)

| Name | Value |
| --- | --- |
| Exchange | `media.operations` (direct, durable) |
| Queue | `media.operations.execute` (durable) |
| Routing key | `operation.execute` |
| Dead-letter exchange | `media.operations.dlx` (direct, durable) |
| Dead-letter queue | `media.operations.execute.dlq` |
| Dead-letter routing key | `operation.execute.dead` |

Messages are persistent. Consumers use manual ACK and `prefetch=1`. Malformed or non-requeued rejects go to the DLQ. This is delivery infrastructure, not a scheduler.
