package com.example.drive.scheduler.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "scheduling_decisions")
public class SchedulingDecision {

	@Id
	private UUID id;

	@Column(name = "operation_id", nullable = false)
	private UUID operationId;

	@Column(name = "worker_id", nullable = false, length = 64)
	private String workerId;

	@Column(nullable = false, length = 32)
	private String policy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected SchedulingDecision() {
	}

	public SchedulingDecision(UUID id, UUID operationId, String workerId, String policy, Instant createdAt) {
		this.id = id;
		this.operationId = operationId;
		this.workerId = workerId;
		this.policy = policy;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getOperationId() {
		return operationId;
	}

	public String getWorkerId() {
		return workerId;
	}

	public String getPolicy() {
		return policy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
