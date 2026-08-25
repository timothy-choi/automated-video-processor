package com.example.drive.job;

import java.util.UUID;

public class IllegalOperationStateException extends RuntimeException {

	private final UUID operationId;

	public IllegalOperationStateException(UUID operationId, String message) {
		super(message);
		this.operationId = operationId;
	}

	public UUID getOperationId() {
		return operationId;
	}
}
