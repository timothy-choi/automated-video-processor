package com.example.drive.job.domain;

public enum JobStatus {
	QUEUED,
	ASSIGNED,
	RUNNING,
	CANCEL_REQUESTED,
	COMPLETED,
	FAILED,
	CANCELLED,
	INTERRUPTED
}
