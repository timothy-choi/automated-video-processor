package com.example.drive.job;

import java.util.UUID;

public class NothingToRetryException extends RuntimeException {

	private final UUID jobId;

	public NothingToRetryException(UUID jobId) {
		super("Job " + jobId + " has no FAILED operations to retry");
		this.jobId = jobId;
	}

	public UUID getJobId() {
		return jobId;
	}
}
