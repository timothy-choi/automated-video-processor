package com.example.drive.worker.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.example.drive.job.domain.OperationType;
import com.example.drive.worker.domain.Worker;
import com.example.drive.worker.domain.WorkerStatus;

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
		Instant registeredAt,
		Instant updatedAt
) {
	public static WorkerResponse from(Worker worker) {
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
				worker.getRegisteredAt(),
				worker.getUpdatedAt()
		);
	}
}
