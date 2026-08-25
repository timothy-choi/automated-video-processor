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

	public Job(UUID id, String inputUri, JobPriority priority, Instant deadline, Instant now) {
		this.id = id;
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
}
