package com.example.drive.job.dto;

import java.util.List;
import java.util.UUID;

public record OperationAttemptsResponse(
		UUID jobId,
		UUID operationId,
		List<AttemptResponse> attempts
) {
}
