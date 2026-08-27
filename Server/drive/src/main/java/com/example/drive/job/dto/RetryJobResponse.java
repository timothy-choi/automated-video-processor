package com.example.drive.job.dto;

import java.util.List;
import java.util.UUID;

import com.example.drive.job.domain.JobStatus;

public record RetryJobResponse(
		UUID jobId,
		JobStatus jobStatus,
		List<RetryOperationResponse> retriedOperations
) {
}
