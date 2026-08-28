package com.example.drive.dispatch;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.drive.observability.TracePropagation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dispatch_outbox")
public class DispatchOutbox {

	@Id
	private UUID id;

	@Column(name = "operation_id", nullable = false, unique = true)
	private UUID operationId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload_json", nullable = false)
	private String payloadJson;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private OutboxStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "publish_attempts", nullable = false)
	private int publishAttempts;

	@Column(name = "routing_key", nullable = false, length = 128)
	private String routingKey;

	@Column(name = "worker_id", length = 64)
	private String workerId;

	@Column(name = "traceparent", length = 128)
	private String traceparent;

	@Column(name = "tracestate", length = 512)
	private String tracestate;

	protected DispatchOutbox() {
	}

	public DispatchOutbox(UUID id, UUID operationId, String payloadJson, Instant now) {
		this(id, operationId, payloadJson, now, DispatchTopology.ROUTING_KEY, null);
	}

	public DispatchOutbox(
			UUID id,
			UUID operationId,
			String payloadJson,
			Instant now,
			String routingKey,
			String workerId
	) {
		this.id = id;
		this.operationId = operationId;
		this.payloadJson = payloadJson;
		this.status = OutboxStatus.PENDING;
		this.createdAt = now;
		this.publishAttempts = 0;
		this.routingKey = routingKey;
		this.workerId = workerId;
		TracePropagation.Captured captured = TracePropagation.capture();
		this.traceparent = captured.traceparent();
		this.tracestate = captured.tracestate();
	}

	public UUID getId() {
		return id;
	}

	public UUID getOperationId() {
		return operationId;
	}

	public String getPayloadJson() {
		return payloadJson;
	}

	public OutboxStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public int getPublishAttempts() {
		return publishAttempts;
	}

	public String getRoutingKey() {
		return routingKey;
	}

	public String getWorkerId() {
		return workerId;
	}

	public String getTraceparent() {
		return traceparent;
	}

	public String getTracestate() {
		return tracestate;
	}

	public void markSent(Instant now) {
		status = OutboxStatus.SENT;
		sentAt = now;
	}

	public void recordFailedAttempt() {
		publishAttempts++;
	}
}
