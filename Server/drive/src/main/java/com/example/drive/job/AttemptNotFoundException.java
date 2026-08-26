package com.example.drive.job;

import java.util.UUID;

public class AttemptNotFoundException extends RuntimeException {

	private final UUID attemptId;

	public AttemptNotFoundException(UUID attemptId) {
		super("Attempt not found: " + attemptId);
		this.attemptId = attemptId;
	}

	public UUID getAttemptId() {
		return attemptId;
	}
}
