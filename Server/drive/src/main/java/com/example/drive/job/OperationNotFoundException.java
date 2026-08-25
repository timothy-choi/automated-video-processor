package com.example.drive.job;

import java.util.UUID;

public class OperationNotFoundException extends RuntimeException {

	private final UUID operationId;

	public OperationNotFoundException(UUID operationId) {
		super("Operation " + operationId + " was not found");
		this.operationId = operationId;
	}

	public UUID getOperationId() {
		return operationId;
	}
}
