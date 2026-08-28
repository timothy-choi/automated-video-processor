package com.example.drive.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.drive.job.domain.Artifact;
import com.example.drive.job.domain.AttemptStatus;
import com.example.drive.job.domain.ExecutionAttempt;
import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.JobStatus;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.dto.JobTimelineResponse;
import com.example.drive.job.dto.OperationTimingResponse;
import com.example.drive.job.dto.TimelineEventResponse;
import com.example.drive.job.dto.TimelineEventType;
import com.example.drive.observability.OperationTimings;
import com.example.drive.scheduler.domain.SchedulingDecision;

/**
 * Derives a user-facing Job timeline from persisted domain records.
 *
 * <p>Does not query telemetry backends. Does not mutate Job/Operation state.
 */
@Component
class JobTimelineAssembler {

	private static final EnumMap<TimelineEventType, Integer> PRECEDENCE = precedence();
	private static final Pattern TRACEPARENT = Pattern.compile(
			"^\\s*[0-9a-fA-F]{2}-([0-9a-fA-F]{32})-[0-9a-fA-F]{16}-[0-9a-fA-F]{2}\\s*$"
	);

	JobTimelineResponse assemble(
			Job job,
			List<Artifact> artifacts,
			List<ExecutionAttempt> attempts,
			List<SchedulingDecision> decisions
	) {
		Map<UUID, Operation> operationsById = new HashMap<>();
		for (Operation operation : job.getOperations()) {
			operationsById.put(operation.getId(), operation);
		}

		Map<UUID, List<SchedulingDecision>> decisionsByOp = new HashMap<>();
		for (SchedulingDecision decision : decisions) {
			decisionsByOp.computeIfAbsent(decision.getOperationId(), ignored -> new ArrayList<>()).add(decision);
		}
		Map<UUID, List<ExecutionAttempt>> attemptsByOp = new HashMap<>();
		for (ExecutionAttempt attempt : attempts) {
			UUID operationId = attempt.getOperation().getId();
			attemptsByOp.computeIfAbsent(operationId, ignored -> new ArrayList<>()).add(attempt);
		}

		List<SortableEvent> sortable = new ArrayList<>();
		sortable.add(new SortableEvent(jobCreated(job), job.getId()));
		for (Operation operation : job.getOperations()) {
			sortable.add(new SortableEvent(operationQueued(operation), operation.getId()));
			if (isRetryQueued(operation, attemptsByOp.getOrDefault(operation.getId(), List.of()))) {
				sortable.add(new SortableEvent(operationRetried(operation), operation.getId()));
			}
		}
		for (SchedulingDecision decision : decisions) {
			Operation operation = operationsById.get(decision.getOperationId());
			if (operation != null) {
				sortable.add(new SortableEvent(operationAssigned(operation, decision), decision.getId()));
			}
		}
		Set<UUID> operationsWithEndedAttempt = new HashSet<>();
		for (ExecutionAttempt attempt : attempts) {
			Operation operation = operationsById.get(attempt.getOperation().getId());
			if (operation == null) {
				continue;
			}
			TimelineEventResponse started = operationStarted(operation, attempt);
			if (started != null) {
				sortable.add(new SortableEvent(started, attempt.getId()));
			}
			TimelineEventResponse ended = attemptEnded(operation, attempt);
			if (ended != null) {
				sortable.add(new SortableEvent(ended, attempt.getId()));
				operationsWithEndedAttempt.add(operation.getId());
			}
		}
		for (Operation operation : job.getOperations()) {
			if (operation.getStatus() == OperationStatus.CANCELLED
					&& operation.getCompletedAt() != null
					&& !operationsWithEndedAttempt.contains(operation.getId())) {
				sortable.add(new SortableEvent(operationCancelledWithoutAttempt(operation), operation.getId()));
			}
		}
		for (Artifact artifact : artifacts) {
			Operation operation = operationsById.get(artifact.getOperationId());
			sortable.add(new SortableEvent(artifactCreated(artifact, operation), artifact.getId()));
		}
		TimelineEventResponse jobTerminal = jobTerminal(job);
		if (jobTerminal != null) {
			sortable.add(new SortableEvent(jobTerminal, job.getId()));
		}

		sortable.sort(eventOrder());
		List<TimelineEventResponse> events = sortable.stream().map(SortableEvent::event).toList();

		List<OperationTimingResponse> timings = job.getOperations().stream()
				.sorted(Comparator.comparingInt(Operation::getOperationOrder))
				.map(operation -> operationTiming(
						operation,
						decisionsByOp.getOrDefault(operation.getId(), List.of()),
						attemptsByOp.getOrDefault(operation.getId(), List.of())
				))
				.toList();

		Instant completedAt = jobCompletedAt(job);
		return new JobTimelineResponse(
				job.getId(),
				job.getStatus(),
				job.getCreatedAt(),
				job.getUpdatedAt(),
				completedAt,
				millis(OperationTimings.jobE2e(job.getCreatedAt(), completedAt)),
				job.getOperations().size(),
				countStatus(job, OperationStatus.COMPLETED),
				countStatus(job, OperationStatus.FAILED),
				countStatus(job, OperationStatus.CANCELLED),
				artifacts.size(),
				traceId(job.getTraceparent()),
				List.copyOf(events),
				timings
		);
	}

	private static TimelineEventResponse jobCreated(Job job) {
		int n = job.getOperations().size();
		String message = n == 1 ? "Job created with 1 operation" : "Job created with " + n + " operations";
		return event(
				job.getCreatedAt(),
				TimelineEventType.JOB_CREATED,
				message,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	private static TimelineEventResponse operationQueued(Operation operation) {
		return event(
				operation.getCreatedAt(),
				TimelineEventType.OPERATION_QUEUED,
				operation.getType() + " queued",
				operation.getId(),
				operation.getType(),
				operation.getOperationOrder(),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	private static boolean isRetryQueued(Operation operation, List<ExecutionAttempt> attempts) {
		Instant queuedAt = operation.getQueuedAt();
		Instant createdAt = operation.getCreatedAt();
		if (queuedAt == null || createdAt == null || !queuedAt.isAfter(createdAt)) {
			return false;
		}
		return attempts.stream().anyMatch(attempt -> isFailedLike(attempt.getStatus())
				&& attempt.getStartedAt() != null
				&& attempt.getStartedAt().isBefore(queuedAt));
	}

	private static boolean isFailedLike(AttemptStatus status) {
		return status == AttemptStatus.FAILED || status == AttemptStatus.INTERRUPTED;
	}

	private static TimelineEventResponse operationRetried(Operation operation) {
		return event(
				operation.getQueuedAt(),
				TimelineEventType.OPERATION_RETRIED,
				operation.getType() + " requeued after failure",
				operation.getId(),
				operation.getType(),
				operation.getOperationOrder(),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	private static TimelineEventResponse operationAssigned(Operation operation, SchedulingDecision decision) {
		String message = "Assigned to " + decision.getWorkerId()
				+ " (operation=" + decision.getOperationPolicy()
				+ ", worker=" + decision.getWorkerPolicy() + ")";
		return event(
				decision.getCreatedAt(),
				TimelineEventType.OPERATION_ASSIGNED,
				message,
				operation.getId(),
				operation.getType(),
				operation.getOperationOrder(),
				null,
				null,
				null,
				decision.getWorkerId(),
				decision.getOperationPolicy(),
				decision.getWorkerPolicy(),
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	private static TimelineEventResponse operationStarted(Operation operation, ExecutionAttempt attempt) {
		Instant started = attempt.getStartedAt();
		if (started == null) {
			return null;
		}
		return event(
				started,
				TimelineEventType.OPERATION_STARTED,
				"Attempt " + attempt.getAttemptNumber() + " started on " + attempt.getWorkerId(),
				operation.getId(),
				operation.getType(),
				operation.getOperationOrder(),
				attempt.getId(),
				attempt.getAttemptNumber(),
				AttemptStatus.RUNNING,
				attempt.getWorkerId(),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	private static TimelineEventResponse attemptEnded(Operation operation, ExecutionAttempt attempt) {
		Instant endedAt = attempt.getEndedAt();
		if (endedAt == null) {
			return null;
		}
		TimelineEventType type;
		String message;
		String failureReason = TimelineFailureSanitizer.sanitize(attempt.getFailureReason());
		switch (attempt.getStatus()) {
			case COMPLETED -> {
				type = TimelineEventType.OPERATION_COMPLETED;
				message = "Attempt " + attempt.getAttemptNumber() + " completed";
			}
			case CANCELLED -> {
				type = TimelineEventType.OPERATION_CANCELLED;
				message = "Attempt " + attempt.getAttemptNumber() + " cancelled";
			}
			case FAILED, INTERRUPTED -> {
				type = TimelineEventType.OPERATION_FAILED;
				message = failureReason != null
						? "Attempt " + attempt.getAttemptNumber() + " failed: " + failureReason
						: "Attempt " + attempt.getAttemptNumber() + " failed";
			}
			default -> {
				return null;
			}
		}
		return event(
				endedAt,
				type,
				message,
				operation.getId(),
				operation.getType(),
				operation.getOperationOrder(),
				attempt.getId(),
				attempt.getAttemptNumber(),
				attempt.getStatus(),
				attempt.getWorkerId(),
				null,
				null,
				null,
				null,
				null,
				null,
				positiveMs(attempt.getActualRuntimeMs()),
				type == TimelineEventType.OPERATION_FAILED || type == TimelineEventType.OPERATION_CANCELLED
						? failureReason
						: null
		);
	}

	private static TimelineEventResponse operationCancelledWithoutAttempt(Operation operation) {
		return event(
				operation.getCompletedAt(),
				TimelineEventType.OPERATION_CANCELLED,
				operation.getType() + " cancelled",
				operation.getId(),
				operation.getType(),
				operation.getOperationOrder(),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	private static TimelineEventResponse artifactCreated(Artifact artifact, Operation operation) {
		String label = artifact.getType() != null ? artifact.getType().name() : "artifact";
		return event(
				artifact.getCreatedAt(),
				TimelineEventType.ARTIFACT_CREATED,
				label + " available",
				artifact.getOperationId(),
				operation != null ? operation.getType() : null,
				operation != null ? operation.getOperationOrder() : null,
				null,
				null,
				null,
				null,
				null,
				null,
				artifact.getId(),
				artifact.getType(),
				artifact.getSizeBytes(),
				artifact.getChecksum(),
				null,
				null
		);
	}

	private static TimelineEventResponse jobTerminal(Job job) {
		Instant completedAt = jobCompletedAt(job);
		if (completedAt == null) {
			return null;
		}
		TimelineEventType type;
		String message;
		switch (job.getStatus()) {
			case COMPLETED -> {
				type = TimelineEventType.JOB_COMPLETED;
				message = "Job completed";
			}
			case FAILED -> {
				type = TimelineEventType.JOB_FAILED;
				message = "Job failed";
			}
			case CANCELLED -> {
				type = TimelineEventType.JOB_CANCELLED;
				message = "Job cancelled";
			}
			default -> {
				return null;
			}
		}
		return event(
				completedAt,
				type,
				message,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	private static Instant jobCompletedAt(Job job) {
		if (!isTerminal(job.getStatus())) {
			return null;
		}
		return job.getOperations().stream()
				.map(Operation::getCompletedAt)
				.filter(instant -> instant != null)
				.max(Instant::compareTo)
				.orElse(null);
	}

	private static boolean isTerminal(JobStatus status) {
		return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
	}

	private static OperationTimingResponse operationTiming(
			Operation operation,
			List<SchedulingDecision> decisions,
			List<ExecutionAttempt> attempts
	) {
		Instant queuedAt = operation.getQueuedAt() != null ? operation.getQueuedAt() : operation.getCreatedAt();
		Instant assignedAt = operation.getAssignedAt() != null
				? operation.getAssignedAt()
				: lastDecisionCreatedAt(decisions);
		ExecutionAttempt latestAttempt = latestAttempt(attempts);
		Instant startedAt = latestAttempt != null ? latestAttempt.getStartedAt() : operation.getStartedAt();
		Instant assignmentReference = matchingDecisionCreatedAt(decisions, startedAt);
		if (assignmentReference == null) {
			assignmentReference = assignedAt;
		}
		Instant completedAt = operation.getCompletedAt();
		Long executionRuntimeMs = null;
		if (operation.getStatus() != OperationStatus.QUEUED && operation.getStatus() != OperationStatus.ASSIGNED) {
			if (latestAttempt != null && latestAttempt.getEndedAt() != null) {
				executionRuntimeMs = positiveMs(latestAttempt.getActualRuntimeMs());
				if (executionRuntimeMs == null) {
					executionRuntimeMs = millis(OperationTimings.jobE2e(latestAttempt.getStartedAt(), latestAttempt.getEndedAt()));
				}
			}
			else if (operation.isTerminal()) {
				executionRuntimeMs = positiveMs(operation.getActualRuntimeMs());
			}
		}
		return new OperationTimingResponse(
				operation.getId(),
				operation.getType(),
				operation.getOperationOrder(),
				operation.getStatus(),
				queuedAt,
				assignedAt,
				startedAt,
				completedAt,
				millis(OperationTimings.queueWait(queuedAt, assignedAt)),
				millis(OperationTimings.assignmentWait(assignmentReference, startedAt)),
				executionRuntimeMs,
				millis(OperationTimings.jobE2e(operation.getCreatedAt(), completedAt)),
				attempts.size(),
				lastWorker(attempts, decisions, operation)
		);
	}

	private static Instant lastDecisionCreatedAt(List<SchedulingDecision> decisions) {
		Instant latest = null;
		for (SchedulingDecision decision : decisions) {
			if (decision.getCreatedAt() != null && (latest == null || decision.getCreatedAt().isAfter(latest))) {
				latest = decision.getCreatedAt();
			}
		}
		return latest;
	}

	private static Instant matchingDecisionCreatedAt(List<SchedulingDecision> decisions, Instant startedAt) {
		if (startedAt == null) {
			return lastDecisionCreatedAt(decisions);
		}
		Instant latest = null;
		for (SchedulingDecision decision : decisions) {
			if (decision.getCreatedAt() == null || decision.getCreatedAt().isAfter(startedAt)) {
				continue;
			}
			if (latest == null || decision.getCreatedAt().isAfter(latest)) {
				latest = decision.getCreatedAt();
			}
		}
		return latest != null ? latest : lastDecisionCreatedAt(decisions);
	}

	private static ExecutionAttempt latestAttempt(List<ExecutionAttempt> attempts) {
		ExecutionAttempt latest = null;
		for (ExecutionAttempt attempt : attempts) {
			if (latest == null || attempt.getAttemptNumber() > latest.getAttemptNumber()) {
				latest = attempt;
			}
		}
		return latest;
	}

	private static String lastWorker(
			List<ExecutionAttempt> attempts,
			List<SchedulingDecision> decisions,
			Operation operation
	) {
		ExecutionAttempt latestAttempt = latestAttempt(attempts);
		if (latestAttempt != null && latestAttempt.getWorkerId() != null && !latestAttempt.getWorkerId().isBlank()) {
			return latestAttempt.getWorkerId();
		}
		if (!decisions.isEmpty()) {
			SchedulingDecision last = decisions.get(decisions.size() - 1);
			if (last.getWorkerId() != null && !last.getWorkerId().isBlank()) {
				return last.getWorkerId();
			}
		}
		return operation.getAssignedWorkerId();
	}

	private static int countStatus(Job job, OperationStatus status) {
		int count = 0;
		for (Operation operation : job.getOperations()) {
			if (operation.getStatus() == status) {
				count++;
			}
		}
		return count;
	}

	static String traceId(String traceparent) {
		if (traceparent == null || traceparent.isBlank()) {
			return null;
		}
		Matcher matcher = TRACEPARENT.matcher(traceparent);
		if (!matcher.matches()) {
			return null;
		}
		return matcher.group(1).toLowerCase();
	}

	static Long positiveMs(Long value) {
		if (value == null || value < 0) {
			return null;
		}
		return value;
	}

	private static Long millis(java.util.Optional<Duration> duration) {
		return duration.map(Duration::toMillis).orElse(null);
	}

	private static Comparator<SortableEvent> eventOrder() {
		return Comparator.comparing((SortableEvent item) -> item.event().timestamp(), Comparator.nullsLast(Instant::compareTo))
				.thenComparing(item -> PRECEDENCE.getOrDefault(item.event().type(), 100))
				.thenComparing(item -> item.event().operationOrder() != null ? item.event().operationOrder() : Integer.MAX_VALUE)
				.thenComparing(item -> item.sortKey(), Comparator.nullsLast(UUID::compareTo))
				.thenComparing(item -> item.event().type().name());
	}

	private record SortableEvent(TimelineEventResponse event, UUID sortKey) {
	}

	private static EnumMap<TimelineEventType, Integer> precedence() {
		EnumMap<TimelineEventType, Integer> map = new EnumMap<>(TimelineEventType.class);
		int i = 0;
		map.put(TimelineEventType.JOB_CREATED, i++);
		map.put(TimelineEventType.OPERATION_QUEUED, i++);
		map.put(TimelineEventType.OPERATION_RETRIED, i++);
		map.put(TimelineEventType.OPERATION_ASSIGNED, i++);
		map.put(TimelineEventType.OPERATION_STARTED, i++);
		map.put(TimelineEventType.ARTIFACT_CREATED, i++);
		map.put(TimelineEventType.OPERATION_COMPLETED, i++);
		map.put(TimelineEventType.OPERATION_FAILED, i++);
		map.put(TimelineEventType.OPERATION_CANCELLED, i++);
		map.put(TimelineEventType.JOB_COMPLETED, i++);
		map.put(TimelineEventType.JOB_FAILED, i++);
		map.put(TimelineEventType.JOB_CANCELLED, i++);
		return map;
	}

	private static TimelineEventResponse event(
			Instant timestamp,
			TimelineEventType type,
			String message,
			UUID operationId,
			com.example.drive.job.domain.OperationType operationType,
			Integer operationOrder,
			UUID attemptId,
			Integer attemptNumber,
			AttemptStatus outcome,
			String workerId,
			String operationPolicy,
			String workerPolicy,
			UUID artifactId,
			com.example.drive.job.domain.ArtifactType artifactType,
			Long sizeBytes,
			String checksum,
			Long runtimeMs,
			String failureReason
	) {
		return new TimelineEventResponse(
				timestamp,
				type,
				message,
				operationId,
				operationType,
				operationOrder,
				attemptId,
				attemptNumber,
				outcome,
				workerId,
				operationPolicy,
				workerPolicy,
				artifactId,
				artifactType,
				sizeBytes,
				checksum,
				runtimeMs,
				failureReason
		);
	}
}
