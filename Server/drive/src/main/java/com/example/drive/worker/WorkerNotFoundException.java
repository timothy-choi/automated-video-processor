package com.example.drive.worker;

public class WorkerNotFoundException extends RuntimeException {

	private final String workerId;

	public WorkerNotFoundException(String workerId) {
		super("Worker " + workerId + " was not found");
		this.workerId = workerId;
	}

	public String getWorkerId() {
		return workerId;
	}
}
