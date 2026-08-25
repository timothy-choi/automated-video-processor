package com.example.drive.job.dto;

import java.time.Instant;
import java.util.List;

import com.example.drive.job.domain.JobPriority;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateJobRequest(
		@NotBlank(message = "inputUri is required")
		@Size(max = 2048, message = "inputUri must be at most 2048 characters")
		String inputUri,

		@NotNull(message = "operations are required")
		@NotEmpty(message = "at least one operation is required")
		List<@Valid CreateOperationRequest> operations,

		JobPriority priority,

		Instant deadline
) {
	public JobPriority priorityOrDefault() {
		return priority == null ? JobPriority.NORMAL : priority;
	}
}
