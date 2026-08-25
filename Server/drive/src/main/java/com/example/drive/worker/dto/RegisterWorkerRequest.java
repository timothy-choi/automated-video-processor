package com.example.drive.worker.dto;

import java.util.List;

public record RegisterWorkerRequest(
		String workerId,
		String hostname,
		List<String> supportedOperations,
		List<String> supportedCodecs,
		String cpuArchitecture,
		Integer cpuCores,
		Long memoryBytes,
		String ffmpegVersion
) {
}
