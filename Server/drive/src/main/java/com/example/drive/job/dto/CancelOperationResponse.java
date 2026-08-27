package com.example.drive.job.dto;

import java.util.UUID;

import com.example.drive.job.domain.JobStatus;
import com.example.drive.job.domain.OperationStatus;

public record CancelOperationResponse(
		UUID jobId,
		JobStatus jobStatus,
		UUID operationId,
		OperationStatus operationStatus
) {
}
