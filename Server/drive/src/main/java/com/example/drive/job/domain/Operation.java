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
}
