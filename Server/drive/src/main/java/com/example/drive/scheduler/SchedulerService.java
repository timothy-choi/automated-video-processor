package com.example.drive.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.dispatch.AssignmentJson;
import com.example.drive.dispatch.DispatchOutbox;
import com.example.drive.dispatch.DispatchOutboxRepository;
import com.example.drive.dispatch.DispatchTopology;
import com.example.drive.job.IllegalOperationStateException;
import com.example.drive.job.InvalidJobRequestException;
import com.example.drive.job.OperationNotFoundException;
import com.example.drive.job.WorkerNotEligibleException;
import com.example.drive.job.domain.AttemptStatus;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.domain.OperationType;
import com.example.drive.job.repository.ExecutionAttemptRepository;
import com.example.drive.job.repository.OperationRepository;
import com.example.drive.scheduler.domain.SchedulingDecision;
import com.example.drive.scheduler.dto.AssignOperationRequest;
import com.example.drive.scheduler.dto.AssignOperationResponse;
import com.example.drive.scheduler.dto.SchedulableOperationResponse;
import com.example.drive.scheduler.dto.SchedulerSnapshotResponse;
import com.example.drive.scheduler.repository.SchedulingDecisionRepository;
import com.example.drive.worker.WorkerNotFoundException;
import com.example.drive.worker.domain.Worker;
import com.example.drive.worker.domain.WorkerStatus;
import com.example.drive.worker.dto.WorkerResponse;
import com.example.drive.worker.repository.WorkerRepository;
import com.example.drive.observability.LogCorrelation;
import com.example.drive.observability.MediaAttributes;
import com.example.drive.observability.MediaMetrics;
import com.example.drive.observability.MediaSpans;
import com.example.drive.observability.OperationTimings;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import jakarta.persistence.EntityManager;

@Service
public class SchedulerService {

	public static final String FIFO_POLICY = "FIFO";
	public static final int ROUND_ROBIN_LOCK_KEY = 400_002;

	private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

	private final EntityManager entityManager;
	private final OperationRepository operationRepository;
	private final WorkerRepository workerRepository;
	private final ExecutionAttemptRepository attemptRepository;
	private final SchedulingDecisionRepository decisionRepository;
	private final DispatchOutboxRepository outboxRepository;
	private final Clock clock;
	private final MediaMetrics mediaMetrics;
	private final Tracer tracer;

	public SchedulerService(
			EntityManager entityManager,
			OperationRepository operationRepository,
			WorkerRepository workerRepository,
			ExecutionAttemptRepository attemptRepository,
			SchedulingDecisionRepository decisionRepository,
			DispatchOutboxRepository outboxRepository,
			Clock clock,
			MediaMetrics mediaMetrics,
			Tracer tracer
	) {
		this.entityManager = entityManager;
		this.operationRepository = operationRepository;
		this.workerRepository = workerRepository;
		this.attemptRepository = attemptRepository;
		this.decisionRepository = decisionRepository;
		this.outboxRepository = outboxRepository;
		this.clock = clock;
		this.mediaMetrics = mediaMetrics;
		this.tracer = tracer;
	}

	@Transactional(readOnly = true)
	public SchedulerSnapshotResponse snapshot() {
		List<SchedulableOperationResponse> operations = operationRepository.findSchedulableQueued().stream()
				.map(SchedulableOperationResponse::from)
				.toList();
		Map<String, Integer> running = runningAttemptCounts();
		List<WorkerResponse> workers = workerRepository.findAllByOrderByIdAsc().stream()
				.map(worker -> WorkerResponse.from(worker, running.getOrDefault(worker.getId(), 0)))
				.toList();
		Map<String, String> cursors = new LinkedHashMap<>();
		putCursor(cursors, OperationType.METADATA);
		putCursor(cursors, OperationType.THUMBNAIL);
		putCursor(cursors, OperationType.AUDIO_EXTRACTION);
		putCursor(cursors, OperationType.TRANSCODE_1080P);
		putCursor(cursors, OperationType.H264_TO_AV1);
		return new SchedulerSnapshotResponse(operations, workers, cursors);
	}

	@Transactional
	public AssignOperationResponse assign(AssignOperationRequest request) {
		Span span = MediaSpans.start(tracer, MediaSpans.SCHEDULER_ASSIGN);
		try (Scope ignored = span.makeCurrent()) {
			return assignInSpan(request, span);
		}
		catch (RuntimeException ex) {
			MediaSpans.recordError(span, ex);
			throw ex;
		}
		finally {
			span.end();
		}
	}

	private AssignOperationResponse assignInSpan(AssignOperationRequest request, Span span) {
		String operationPolicy = requireOperationPolicy(request);
		String workerPolicy = requireWorkerPolicy(request);
		String workerId = requireWorkerId(request.workerId());
		UUID operationId = request.operationId();

		lockJobForOperation(operationId);
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		requireQueuedDispatchable(operation);
		if (outboxRepository.existsByOperationId(operationId)) {
			throw new IllegalOperationStateException(
					operationId,
					"Operation " + operationId + " already has a dispatch outbox row"
			);
		}

		Worker worker = workerRepository.findById(workerId)
				.orElseThrow(() -> new WorkerNotFoundException(workerId));
		if (worker.getStatus() != WorkerStatus.AVAILABLE) {
			throw new WorkerNotEligibleException("WORKER_UNAVAILABLE", "Worker is not AVAILABLE: " + workerId);
		}
		if (!worker.getSupportedOperations().contains(operation.getType())) {
			throw new WorkerNotEligibleException(
					"WORKER_CAPABILITY_MISMATCH",
					"Worker " + workerId + " does not advertise " + operation.getType()
			);
		}

		if (WorkerPlacement.ROUND_ROBIN.equals(workerPolicy)) {
			lockRoundRobin(operation.getType());
		}
		requireCurrentPlacement(workerPolicy, operation.getType(), workerId);

		UUID assignmentId = UUID.randomUUID();
		Instant queuedAt = operation.getQueuedAt();
		operation.markAssigned(now, workerId, assignmentId);
		var previousJobStatus = operation.getJob().getStatus();
		operation.getJob().refreshStatusFromOperations(now);
		mediaMetrics.jobTransition(previousJobStatus, operation.getJob().getStatus());
		OperationTimings.queueWait(queuedAt, now).ifPresent(wait ->
				mediaMetrics.recordQueueWait(operation.getType().name(), wait)
		);
		String routingKey = DispatchTopology.workerRoutingKey(workerId);
		String payload = AssignmentJson.v3(
				operation.getId(),
				operation.getJob().getId(),
				operation.getType().name(),
				operation.getJob().getInputUri(),
				workerId,
				now,
				operationPolicy,
				workerPolicy,
				assignmentId
		);
		outboxRepository.save(new DispatchOutbox(
				UUID.randomUUID(),
				operationId,
				payload,
				now,
				routingKey,
				workerId
		));
		SchedulingDecision decision = decisionRepository.save(new SchedulingDecision(
				assignmentId,
				operationId,
				workerId,
				operationPolicy,
				workerPolicy,
				now
		));
		int selectedLoad = runningAttemptCounts().getOrDefault(workerId, 0);
		MediaSpans.set(span, MediaAttributes.OPERATION_ID, operation.getId().toString());
		MediaSpans.set(span, MediaAttributes.JOB_ID, operation.getJob().getId().toString());
		MediaSpans.set(span, MediaAttributes.OPERATION_TYPE, operation.getType().name());
		MediaSpans.set(span, MediaAttributes.WORKER_ID, workerId);
		MediaSpans.set(span, MediaAttributes.OPERATION_POLICY, operationPolicy);
		MediaSpans.set(span, MediaAttributes.WORKER_POLICY, workerPolicy);
		if (WorkerPlacement.LEAST_LOADED.equals(workerPolicy)) {
			MediaSpans.set(span, MediaAttributes.ACTIVE_OPERATIONS, (long) selectedLoad);
		}
		mediaMetrics.schedulerDecision(workerPolicy);
		try (LogCorrelation correlation = LogCorrelation.open(
				operation.getJob().getId(),
				operation.getId(),
				null,
				workerId
		)) {
			log.info(
					"event=scheduling_decision operationId={} jobId={} workerId={} operationType={} operation_policy={} worker_policy={} selected_load={} decisionId={} routingKey={}",
					operation.getId(),
					operation.getJob().getId(),
					workerId,
					operation.getType(),
					operationPolicy,
					workerPolicy,
					selectedLoad,
					decision.getId(),
					routingKey
			);
		}
		return new AssignOperationResponse(
				decision.getId(),
				operation.getId(),
				operation.getJob().getId(),
				workerId,
				operationPolicy,
				operationPolicy,
				workerPolicy,
				now,
				routingKey
		);
	}

	private void putCursor(Map<String, String> cursors, OperationType type) {
		decisionRepository.findLatestWorker(WorkerPlacement.ROUND_ROBIN, type.name())
				.ifPresent(workerId -> cursors.put(type.name(), workerId));
	}

	private void requireCurrentPlacement(String workerPolicy, OperationType type, String workerId) {
		List<String> eligible = eligibleWorkerIds(type);
		if (eligible.isEmpty()) {
			throw new WorkerNotEligibleException(
					"NO_ELIGIBLE_WORKER",
					"No AVAILABLE capable worker for " + type
			);
		}
		if (WorkerPlacement.LEAST_LOADED.equals(workerPolicy)) {
			if (!eligible.contains(workerId)) {
				throw new WorkerNotEligibleException(
						"WORKER_NOT_ELIGIBLE",
						"Worker " + workerId + " is not currently eligible for " + type
				);
			}
			return;
		}
		String last = WorkerPlacement.ROUND_ROBIN.equals(workerPolicy)
				? decisionRepository.findLatestWorker(WorkerPlacement.ROUND_ROBIN, type.name()).orElse(null)
				: null;
		String expected = WorkerPlacement.next(workerPolicy, eligible, last);
		if (!workerId.equals(expected)) {
			throw new WorkerNotEligibleException(
					"WORKER_PLACEMENT_CONFLICT",
					"Worker " + workerId + " is not the current " + workerPolicy + " placement; expected " + expected
			);
		}
	}

	private Map<String, Integer> runningAttemptCounts() {
		Map<String, Integer> counts = new HashMap<>();
		for (ExecutionAttemptRepository.WorkerRunningCount row : attemptRepository.countRunningByWorker(AttemptStatus.RUNNING)) {
			counts.put(row.getWorkerId(), (int) row.getRunningCount());
		}
		return counts;
	}

	private List<String> eligibleWorkerIds(OperationType type) {
		return workerRepository.findAllByOrderByIdAsc().stream()
				.filter(worker -> worker.getStatus() == WorkerStatus.AVAILABLE)
				.filter(worker -> worker.getSupportedOperations().contains(type))
				.map(Worker::getId)
				.toList();
	}

	private void lockRoundRobin(OperationType type) {
		entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:k1, hashtext(:key))")
				.setParameter("k1", ROUND_ROBIN_LOCK_KEY)
				.setParameter("key", type.name())
				.getSingleResult();
	}

	private void requireQueuedDispatchable(Operation operation) {
		if (operation.getStatus() != OperationStatus.QUEUED) {
			throw new IllegalOperationStateException(
					operation.getId(),
					"Operation " + operation.getId() + " cannot move from " + operation.getStatus() + " to ASSIGNED"
			);
		}
		if (!operation.getType().isExecutable()) {
			throw new InvalidJobRequestException(
					"UNSUPPORTED_OPERATION_TYPE",
					"Scheduler assignment supports METADATA, THUMBNAIL, AUDIO_EXTRACTION, TRANSCODE_1080P, and H264_TO_AV1 only"
			);
		}
		String inputUri = operation.getJob().getInputUri();
		if (!isDispatchableUri(inputUri)) {
			throw new InvalidJobRequestException(
					"UNSUPPORTED_INPUT_URI",
					"Scheduler assignment supports file: and s3: input URIs only"
			);
		}
	}

	private static String requireOperationPolicy(AssignOperationRequest request) {
		String raw = firstNonBlank(request.operationPolicy(), request.policy());
		if (raw == null) {
			throw new InvalidJobRequestException("UNSUPPORTED_POLICY", "operationPolicy is required");
		}
		if (!FIFO_POLICY.equalsIgnoreCase(raw)) {
			throw new InvalidJobRequestException(
					"UNSUPPORTED_POLICY",
					"Unsupported operation policy '" + raw.trim() + "'; implemented: FIFO"
			);
		}
		return FIFO_POLICY;
	}

	private static String requireWorkerPolicy(AssignOperationRequest request) {
		String raw = firstNonBlank(request.workerPolicy(), null);
		if (raw == null) {
			if (request.policy() != null && FIFO_POLICY.equalsIgnoreCase(request.policy().trim())) {
				return WorkerPlacement.LEXICOGRAPHIC;
			}
			throw new InvalidJobRequestException("UNSUPPORTED_POLICY", "workerPolicy is required");
		}
		String value = raw.trim().toUpperCase(Locale.ROOT);
		if (WorkerPlacement.isSupported(value)) {
			return value;
		}
		throw new InvalidJobRequestException(
				"UNSUPPORTED_POLICY",
				"Unsupported worker placement policy '" + raw.trim() + "'; implemented: LEXICOGRAPHIC, ROUND_ROBIN, LEAST_LOADED"
		);
	}

	private static String firstNonBlank(String primary, String fallback) {
		if (primary != null && !primary.isBlank()) {
			return primary.trim();
		}
		if (fallback != null && !fallback.isBlank()) {
			return fallback.trim();
		}
		return null;
	}

	private static String requireWorkerId(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "workerId is required");
		}
		return raw.trim();
	}

	private static boolean isDispatchableUri(String inputUri) {
		if (inputUri == null) {
			return false;
		}
		String lower = inputUri.toLowerCase(Locale.ROOT);
		return lower.startsWith("file:") || lower.startsWith("s3:");
	}

	private void lockJobForOperation(UUID operationId) {
		@SuppressWarnings("unchecked")
		List<Object> rows = entityManager.createNativeQuery("""
				SELECT j.id
				FROM jobs j
				JOIN operations o ON o.job_id = j.id
				WHERE o.id = :id
				FOR UPDATE OF j
				""")
				.setParameter("id", operationId)
				.getResultList();
		if (rows.isEmpty()) {
			throw new OperationNotFoundException(operationId);
		}
	}
}
