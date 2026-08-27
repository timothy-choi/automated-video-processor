package com.example.drive.job.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "jobs")
public class Job {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@Column(name = "input_uri", nullable = false)
	private String inputUri;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private JobStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private JobPriority priority;

	@Column
	private Instant deadline;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("operationOrder ASC")
	private List<Operation> operations = new ArrayList<>();

	protected Job() {
	}

	public Job(UUID id, UUID accountId, String inputUri, JobPriority priority, Instant deadline, Instant now) {
		this.id = id;
		this.accountId = accountId;
		this.inputUri = inputUri;
		this.status = JobStatus.QUEUED;
		this.priority = priority;
		this.deadline = deadline;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public void addOperation(Operation operation) {
		operations.add(operation);
		operation.setJob(this);
	}

	public UUID getId() {
		return id;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public String getInputUri() {
		return inputUri;
	}

	public JobStatus getStatus() {
		return status;
	}

	public JobPriority getPriority() {
		return priority;
	}

	public Instant getDeadline() {
		return deadline;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public List<Operation> getOperations() {
		return Collections.unmodifiableList(operations);
	}

	public void markRunningIfQueued(Instant now) {
		if (status == JobStatus.QUEUED) {
			status = JobStatus.RUNNING;
			updatedAt = now;
		}
	}

	public void refreshStatusFromOperations(Instant now) {
		// Callers must hold a row lock on this Job so concurrent terminal
		// transitions serialize and recompute from the same committed operation set.
		boolean anyFailed = false;
		boolean anyRunning = false;
		boolean anyCancelRequested = false;
		boolean anyAssigned = false;
		boolean anyQueued = false;
		boolean anyCompleted = false;
		boolean anyCancelled = false;
		for (Operation operation : operations) {
			switch (operation.getStatus()) {
				case FAILED -> anyFailed = true;
				case RUNNING -> anyRunning = true;
				case CANCEL_REQUESTED -> anyCancelRequested = true;
				case ASSIGNED -> anyAssigned = true;
				case QUEUED -> anyQueued = true;
				case COMPLETED -> anyCompleted = true;
				case CANCELLED -> anyCancelled = true;
			}
		}

		JobStatus next;
		if (anyFailed) {
			next = JobStatus.FAILED;
		}
		else if (anyRunning) {
			next = JobStatus.RUNNING;
		}
		else if (anyCancelRequested) {
			next = JobStatus.CANCEL_REQUESTED;
		}
		else if (anyCompleted && (anyQueued || anyAssigned)) {
			next = JobStatus.RUNNING;
		}
		else if (anyAssigned) {
			next = JobStatus.ASSIGNED;
		}
		else if (anyQueued) {
			next = JobStatus.QUEUED;
		}
		else if (anyCancelled) {
			next = JobStatus.CANCELLED;
		}
		else if (anyCompleted) {
			next = JobStatus.COMPLETED;
		}
		else {
			next = JobStatus.QUEUED;
		}

		status = next;
		updatedAt = now;
	}
}
