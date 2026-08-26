package com.example.drive.scheduler.dto;

import java.util.List;
import java.util.Map;

import com.example.drive.worker.dto.WorkerResponse;

public record SchedulerSnapshotResponse(
		List<SchedulableOperationResponse> operations,
		List<WorkerResponse> workers,
		Map<String, String> roundRobinCursors
) {
}
