package com.example.drive.job.dto;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.example.drive.job.domain.Operation;

public record JobOperationsResponse(
		UUID jobId,
		List<OperationResponse> operations
) {
	public static JobOperationsResponse from(UUID jobId, List<Operation> operations) {
		List<OperationResponse> ordered = operations.stream()
				.sorted(Comparator.comparingInt(Operation::getOperationOrder))
				.map(OperationResponse::from)
				.toList();
		return new JobOperationsResponse(jobId, ordered);
	}
}
