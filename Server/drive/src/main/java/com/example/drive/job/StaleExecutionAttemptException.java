package com.example.drive.job;

import java.util.UUID;

public class StaleExecutionAttemptException extends RuntimeException {

	private final UUID attemptId;

	public StaleExecutionAttemptException(UUID attemptId, String message) {
		super(message);
		this.attemptId = attemptId;
	}

	public UUID getAttemptId() {
		return attemptId;
	}
}
