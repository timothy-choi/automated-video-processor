package com.example.drive.job;

import java.time.Instant;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.example.drive.job.domain.JobPriority;
import com.example.drive.job.domain.JobStatus;
import com.example.drive.job.domain.OperationType;

/**
 * Validated GET /jobs query. Sort fields are a closed set so callers cannot
 * inject arbitrary database column names.
 */
public record JobListQuery(
		int page,
		int size,
		JobStatus status,
		OperationType operationType,
		JobPriority priority,
		Instant createdAfter,
		Instant createdBefore,
		JobListSortField sort,
		Sort.Direction direction
) {
	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	public static JobListQuery parse(
			int page,
			int size,
			String status,
			String operationType,
			String priority,
			Instant createdAfter,
			Instant createdBefore,
			String sort,
			String direction
	) {
		if (page < 0) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "page must be >= 0");
		}
		if (size <= 0) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "size must be > 0");
		}
		if (size > MAX_SIZE) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "size must be <= " + MAX_SIZE);
		}
		if (createdAfter != null && createdBefore != null && createdAfter.isAfter(createdBefore)) {
			throw new InvalidJobRequestException(
					"VALIDATION_FAILED",
					"createdAfter must not be after createdBefore"
			);
		}
		return new JobListQuery(
				page,
				size,
				parseEnum(status, JobStatus.class, "status"),
				parseEnum(operationType, OperationType.class, "operationType"),
				parseEnum(priority, JobPriority.class, "priority"),
				createdAfter,
				createdBefore,
				parseSort(sort),
				parseDirection(direction)
		);
	}

	public Pageable toPageable() {
		Sort.Order primary = new Sort.Order(direction, sort.property());
		Sort.Order tieBreak = new Sort.Order(direction, "id");
		return PageRequest.of(page, size, Sort.by(primary, tieBreak));
	}

	private static JobListSortField parseSort(String raw) {
		if (raw == null || raw.isBlank()) {
			return JobListSortField.CREATED_AT;
		}
		return switch (raw.trim()) {
			case "createdAt" -> JobListSortField.CREATED_AT;
			case "updatedAt" -> JobListSortField.UPDATED_AT;
			default -> throw new InvalidJobRequestException(
					"VALIDATION_FAILED",
					"Unsupported sort field: " + raw + ". Allowed: createdAt, updatedAt"
			);
		};
	}

	private static Sort.Direction parseDirection(String raw) {
		if (raw == null || raw.isBlank()) {
			return Sort.Direction.DESC;
		}
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "asc" -> Sort.Direction.ASC;
			case "desc" -> Sort.Direction.DESC;
			default -> throw new InvalidJobRequestException(
					"VALIDATION_FAILED",
					"Unsupported direction: " + raw + ". Allowed: asc, desc"
			);
		};
	}

	private static <T extends Enum<T>> T parseEnum(String raw, Class<T> type, String param) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, raw.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidJobRequestException(
					"INVALID_ENUM_VALUE",
					"Unknown " + param + " value: " + raw
			);
		}
	}
}
