package com.example.drive.job;

import java.util.UUID;

import com.example.drive.job.domain.JobStatus;

public class JobAlreadyTerminalException extends RuntimeException {

	private final UUID jobId;
	private final String code;

	public JobAlreadyTerminalException(UUID jobId, JobStatus status) {
		super("Job " + jobId + " is already " + status + " and cannot be cancelled");
		this.jobId = jobId;
		this.code = "JOB_ALREADY_" + status.name();
	}

	public UUID getJobId() {
		return jobId;
	}

	public String getCode() {
		return code;
	}
}
