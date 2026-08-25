package com.example.drive.worker.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import com.example.drive.job.domain.OperationType;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "workers")
public class Worker {

	@Id
	@Column(length = 64)
	private String id;

	@Column(nullable = false, length = 255)
	private String hostname;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private WorkerStatus status;

	@Column(name = "cpu_architecture", nullable = false, length = 32)
	private String cpuArchitecture;

	@Column(name = "cpu_cores", nullable = false)
	private int cpuCores;

	@Column(name = "memory_bytes", nullable = false)
	private long memoryBytes;

	@Column(name = "ffmpeg_version", length = 64)
	private String ffmpegVersion;

	@Column(name = "registered_at", nullable = false)
	private Instant registeredAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "worker_supported_operations", joinColumns = @JoinColumn(name = "worker_id"))
	@Column(name = "operation_type", nullable = false, length = 64)
	@Enumerated(EnumType.STRING)
	private Set<OperationType> supportedOperations = new LinkedHashSet<>();

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "worker_supported_codecs", joinColumns = @JoinColumn(name = "worker_id"))
	@Column(name = "codec", nullable = false, length = 32)
	private Set<String> supportedCodecs = new LinkedHashSet<>();

	protected Worker() {
	}

	public Worker(
			String id,
			String hostname,
			String cpuArchitecture,
			int cpuCores,
			long memoryBytes,
			String ffmpegVersion,
			Instant now
	) {
		this.id = id;
		this.hostname = hostname;
		this.status = WorkerStatus.REGISTERED;
		this.cpuArchitecture = cpuArchitecture;
		this.cpuCores = cpuCores;
		this.memoryBytes = memoryBytes;
		this.ffmpegVersion = ffmpegVersion;
		this.registeredAt = now;
		this.updatedAt = now;
	}

	public void refreshRegistration(
			String hostname,
			String cpuArchitecture,
			int cpuCores,
			long memoryBytes,
			String ffmpegVersion,
			Instant now
	) {
		this.hostname = hostname;
		this.status = WorkerStatus.REGISTERED;
		this.cpuArchitecture = cpuArchitecture;
		this.cpuCores = cpuCores;
		this.memoryBytes = memoryBytes;
		this.ffmpegVersion = ffmpegVersion;
		this.updatedAt = now;
	}

	public void replaceCapabilities(Set<OperationType> operations, Set<String> codecs) {
		supportedOperations.clear();
		supportedOperations.addAll(operations);
		supportedCodecs.clear();
		supportedCodecs.addAll(codecs);
	}

	public String getId() {
		return id;
	}

	public String getHostname() {
		return hostname;
	}

	public WorkerStatus getStatus() {
		return status;
	}

	public String getCpuArchitecture() {
		return cpuArchitecture;
	}

	public int getCpuCores() {
		return cpuCores;
	}

	public long getMemoryBytes() {
		return memoryBytes;
	}

	public String getFfmpegVersion() {
		return ffmpegVersion;
	}

	public Instant getRegisteredAt() {
		return registeredAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Set<OperationType> getSupportedOperations() {
		return supportedOperations;
	}

	public Set<String> getSupportedCodecs() {
		return supportedCodecs;
	}
}
