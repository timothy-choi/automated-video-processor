package com.example.drive.job;

import java.util.UUID;

public class StaleAssignmentException extends RuntimeException {

	private final UUID operationId;

	public StaleAssignmentException(UUID operationId, String message) {
		super(message);
		this.operationId = operationId;
	}

	public UUID getOperationId() {
		return operationId;
	}
}
