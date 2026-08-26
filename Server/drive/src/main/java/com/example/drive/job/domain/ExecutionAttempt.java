package com.example.drive.job.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "execution_attempts")
public class ExecutionAttempt {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "operation_id", nullable = false)
	private Operation operation;

	@Column(name = "worker_id", nullable = false, length = 64)
	private String workerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private AttemptStatus status;

	@Column(name = "attempt_number", nullable = false)
	private int attemptNumber;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "lease_expires_at", nullable = false)
	private Instant leaseExpiresAt;

	@Column(name = "actual_runtime_ms")
	private Long actualRuntimeMs;

	@Column(name = "failure_reason")
	private String failureReason;

	protected ExecutionAttempt() {
	}

	public ExecutionAttempt(
			UUID id,
			Operation operation,
			String workerId,
			int attemptNumber,
			Instant now,
			Instant leaseExpiresAt
	) {
		this.id = id;
		this.operation = operation;
		this.workerId = workerId;
		this.status = AttemptStatus.RUNNING;
		this.attemptNumber = attemptNumber;
		this.createdAt = now;
		this.startedAt = now;
		this.leaseExpiresAt = leaseExpiresAt;
	}

	public UUID getId() {
		return id;
	}

	public Operation getOperation() {
		return operation;
	}

	public String getWorkerId() {
		return workerId;
	}

	public AttemptStatus getStatus() {
		return status;
	}

	public int getAttemptNumber() {
		return attemptNumber;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public Instant getLeaseExpiresAt() {
		return leaseExpiresAt;
	}

	public Long getActualRuntimeMs() {
		return actualRuntimeMs;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public void renewLease(Instant leaseExpiresAt) {
		this.leaseExpiresAt = leaseExpiresAt;
	}

	public void markCompleted(Instant now, long runtimeMs) {
		this.status = AttemptStatus.COMPLETED;
		this.endedAt = now;
		this.actualRuntimeMs = runtimeMs;
	}

	public void markFailed(Instant now, Long runtimeMs, String reason) {
		this.status = AttemptStatus.FAILED;
		this.endedAt = now;
		this.actualRuntimeMs = runtimeMs;
		this.failureReason = reason;
	}

	public void markInterrupted(Instant now) {
		this.status = AttemptStatus.INTERRUPTED;
		this.endedAt = now;
		this.failureReason = "lease expired while worker was UNAVAILABLE";
	}
}
