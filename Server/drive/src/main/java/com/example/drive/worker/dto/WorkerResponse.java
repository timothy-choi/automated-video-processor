package com.example.drive.worker.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.example.drive.job.domain.OperationType;
import com.example.drive.worker.domain.Worker;
import com.example.drive.worker.domain.WorkerStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

public record WorkerResponse(
		String id,
		WorkerStatus status,
		String hostname,
		List<OperationType> supportedOperations,
		List<String> supportedCodecs,
		String cpuArchitecture,
		int cpuCores,
		long memoryBytes,
		String ffmpegVersion,
		Instant lastHeartbeat,
		Instant registeredAt,
		Instant updatedAt,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		Integer activeOperations
) {
	public static WorkerResponse from(Worker worker) {
		return from(worker, null);
	}

	public static WorkerResponse from(Worker worker, Integer activeOperations) {
		List<OperationType> operations = worker.getSupportedOperations().stream()
				.sorted(Comparator.comparingInt(Enum::ordinal))
				.toList();
		List<String> codecs = worker.getSupportedCodecs().stream()
				.sorted()
				.toList();
		return new WorkerResponse(
				worker.getId(),
				worker.getStatus(),
				worker.getHostname(),
				operations,
				codecs,
				worker.getCpuArchitecture(),
				worker.getCpuCores(),
				worker.getMemoryBytes(),
				worker.getFfmpegVersion(),
				worker.getLastHeartbeat(),
				worker.getRegisteredAt(),
				worker.getUpdatedAt(),
				activeOperations
		);
	}
}
