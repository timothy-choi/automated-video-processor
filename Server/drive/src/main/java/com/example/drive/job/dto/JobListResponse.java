package com.example.drive.job.dto;

import java.util.List;

public record JobListResponse(
		List<JobSummaryResponse> items,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
}
