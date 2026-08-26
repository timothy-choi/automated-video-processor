package com.example.drive.worker.dto;

import java.time.Instant;

import com.example.drive.worker.domain.Worker;
import com.example.drive.worker.domain.WorkerStatus;

public record HeartbeatResponse(
		String workerId,
		WorkerStatus status,
		Instant lastHeartbeat
) {
	public static HeartbeatResponse from(Worker worker) {
		return new HeartbeatResponse(
				worker.getId(),
				worker.getStatus(),
				worker.getLastHeartbeat()
		);
	}
}
