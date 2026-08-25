package com.example.drive.job;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

	private final UUID jobId;

	public JobNotFoundException(UUID jobId) {
		super("Job " + jobId + " was not found");
		this.jobId = jobId;
	}

	public UUID getJobId() {
		return jobId;
	}
}
