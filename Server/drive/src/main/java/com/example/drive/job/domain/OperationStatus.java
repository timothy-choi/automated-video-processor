package com.example.drive.job.domain;

public enum OperationStatus {
	QUEUED,
	ASSIGNED,
	RUNNING,
	CANCEL_REQUESTED,
	COMPLETED,
	FAILED,
	CANCELLED
}
