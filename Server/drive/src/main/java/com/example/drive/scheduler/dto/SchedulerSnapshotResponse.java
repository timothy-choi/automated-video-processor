package com.example.drive.scheduler.dto;

import java.util.List;

import com.example.drive.worker.dto.WorkerResponse;

public record SchedulerSnapshotResponse(
		List<SchedulableOperationResponse> operations,
		List<WorkerResponse> workers
) {
}
