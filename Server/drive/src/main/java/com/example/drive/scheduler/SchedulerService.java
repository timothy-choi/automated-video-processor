package com.example.drive.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
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
import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.domain.OperationType;
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

import jakarta.persistence.EntityManager;

@Service
public class SchedulerService {

	public static final String FIFO_POLICY = "FIFO";

	private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

	private final EntityManager entityManager;
	private final OperationRepository operationRepository;
	private final WorkerRepository workerRepository;
	private final SchedulingDecisionRepository decisionRepository;
	private final DispatchOutboxRepository outboxRepository;
	private final Clock clock;

	public SchedulerService(
			EntityManager entityManager,
			OperationRepository operationRepository,
			WorkerRepository workerRepository,
			SchedulingDecisionRepository decisionRepository,
			DispatchOutboxRepository outboxRepository,
			Clock clock
	) {
		this.entityManager = entityManager;
		this.operationRepository = operationRepository;
		this.workerRepository = workerRepository;
		this.decisionRepository = decisionRepository;
		this.outboxRepository = outboxRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public SchedulerSnapshotResponse snapshot() {
		List<SchedulableOperationResponse> operations = operationRepository.findSchedulableQueued().stream()
				.map(SchedulableOperationResponse::from)
				.toList();
		List<WorkerResponse> workers = workerRepository.findAllByOrderByIdAsc().stream()
				.map(WorkerResponse::from)
				.toList();
		return new SchedulerSnapshotResponse(operations, workers);
	}

	@Transactional
	public AssignOperationResponse assign(AssignOperationRequest request) {
		String policy = requireFifoPolicy(request.policy());
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

		UUID assignmentId = UUID.randomUUID();
		operation.markAssigned(now, workerId, assignmentId);
		operation.getJob().refreshStatusFromOperations(now);
		String routingKey = DispatchTopology.workerRoutingKey(workerId);
		String payload = AssignmentJson.v3(
				operation.getId(),
				operation.getJob().getId(),
				operation.getType().name(),
				operation.getJob().getInputUri(),
				workerId,
				now,
				policy,
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
				policy,
				now
		));
		log.info(
				"event=scheduling_decision operationId={} jobId={} workerId={} policy={} decisionId={} routingKey={}",
				operation.getId(),
				operation.getJob().getId(),
				workerId,
				policy,
				decision.getId(),
				routingKey
		);
		return new AssignOperationResponse(
				decision.getId(),
				operation.getId(),
				operation.getJob().getId(),
				workerId,
				policy,
				now,
				routingKey
		);
	}

	private void requireQueuedDispatchable(Operation operation) {
		if (operation.getStatus() != OperationStatus.QUEUED) {
			throw new IllegalOperationStateException(
					operation.getId(),
					"Operation " + operation.getId() + " cannot move from " + operation.getStatus() + " to ASSIGNED"
			);
		}
		if (operation.getType() != OperationType.METADATA && operation.getType() != OperationType.THUMBNAIL) {
			throw new InvalidJobRequestException(
					"UNSUPPORTED_OPERATION_TYPE",
					"Scheduler assignment supports METADATA and THUMBNAIL only"
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

	private static String requireFifoPolicy(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new InvalidJobRequestException("UNSUPPORTED_POLICY", "policy is required");
		}
		if (!FIFO_POLICY.equalsIgnoreCase(raw.trim())) {
			throw new InvalidJobRequestException(
					"UNSUPPORTED_POLICY",
					"Unsupported scheduling policy '" + raw.trim() + "'; implemented: FIFO"
			);
		}
		return FIFO_POLICY;
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
