package com.example.drive.job.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.drive.job.IllegalOperationStateException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
		name = "operations",
		uniqueConstraints = @UniqueConstraint(name = "operations_job_order_unique", columnNames = {"job_id", "operation_order"})
)
public class Operation {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_id", nullable = false)
	private Job job;

	@Enumerated(EnumType.STRING)
	@Column(name = "operation_type", nullable = false, length = 64)
	private OperationType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OperationStatus status;

	@Column(name = "operation_order", nullable = false)
	private int operationOrder;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "actual_runtime_ms")
	private Long actualRuntimeMs;

	@Column(name = "failure_reason")
	private String failureReason;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "result_json")
	private Map<String, Object> resultJson;

	protected Operation() {
	}

	public Operation(UUID id, OperationType type, int operationOrder, Instant now) {
		this.id = id;
		this.type = type;
		this.status = OperationStatus.QUEUED;
		this.operationOrder = operationOrder;
		this.createdAt = now;
		this.updatedAt = now;
	}

	void setJob(Job job) {
		this.job = job;
	}

	public UUID getId() {
		return id;
	}

	public Job getJob() {
		return job;
	}

	public OperationType getType() {
		return type;
	}

	public OperationStatus getStatus() {
		return status;
	}

	public int getOperationOrder() {
		return operationOrder;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public Long getActualRuntimeMs() {
		return actualRuntimeMs;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Map<String, Object> getResultJson() {
		return resultJson;
	}

	public void markRunning(Instant now) {
		if (status != OperationStatus.QUEUED) {
			throw new IllegalOperationStateException(
					id,
					"Operation " + id + " cannot move from " + status + " to RUNNING"
			);
		}
		status = OperationStatus.RUNNING;
		startedAt = now;
		updatedAt = now;
	}

	public boolean markCompleted(Instant now, long runtimeMs, Map<String, Object> resultJson) {
		if (status == OperationStatus.COMPLETED) {
			return false;
		}
		if (status != OperationStatus.RUNNING) {
			throw new IllegalOperationStateException(
					id,
					"Operation " + id + " cannot move from " + status + " to COMPLETED"
			);
		}
		status = OperationStatus.COMPLETED;
		completedAt = now;
		actualRuntimeMs = runtimeMs;
		this.resultJson = resultJson;
		updatedAt = now;
		return true;
	}

	public boolean markFailed(Instant now, Long runtimeMs, String reason) {
		if (status == OperationStatus.FAILED) {
			return false;
		}
		if (status != OperationStatus.RUNNING) {
			throw new IllegalOperationStateException(
					id,
					"Operation " + id + " cannot move from " + status + " to FAILED"
			);
		}
		status = OperationStatus.FAILED;
		completedAt = now;
		actualRuntimeMs = runtimeMs;
		failureReason = reason;
		updatedAt = now;
		return true;
	}
}
