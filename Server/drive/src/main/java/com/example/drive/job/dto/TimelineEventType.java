package com.example.drive.job.dto;

/**
 * Constrained lifecycle event types for {@code GET /jobs/{id}/timeline}.
 *
 * <p>Only types that can be proven from persisted timestamps are emitted.
 * Transitions without a dedicated instant (for example {@code CANCEL_REQUESTED})
 * are omitted rather than stamped with {@code updatedAt}.
 */
public enum TimelineEventType {
	JOB_CREATED,
	OPERATION_QUEUED,
	OPERATION_RETRIED,
	OPERATION_ASSIGNED,
	OPERATION_STARTED,
	ARTIFACT_CREATED,
	OPERATION_COMPLETED,
	OPERATION_FAILED,
	OPERATION_CANCELLED,
	JOB_COMPLETED,
	JOB_FAILED,
	JOB_CANCELLED
}
