package com.example.drive.worker.dto;

import java.time.Instant;

import com.example.drive.worker.domain.Worker;
import com.example.drive.worker.domain.WorkerStatus;

public record RegisterWorkerResponse(
		String workerId,
		WorkerStatus status,
		Instant registeredAt,
		Instant updatedAt
) {
	public static RegisterWorkerResponse from(Worker worker) {
		return new RegisterWorkerResponse(
				worker.getId(),
				worker.getStatus(),
				worker.getRegisteredAt(),
				worker.getUpdatedAt()
		);
	}
}
