package com.example.drive.job;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.domain.Artifact;
import com.example.drive.job.domain.ArtifactType;
import com.example.drive.job.domain.AttemptStatus;
import com.example.drive.job.domain.ExecutionAttempt;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.dto.ArtifactCompletionDto;
import com.example.drive.job.dto.ClaimedOperationResponse;
import com.example.drive.job.dto.CompleteOperationRequest;
import com.example.drive.job.dto.FailOperationRequest;
import com.example.drive.job.dto.MetadataResultDto;
import com.example.drive.job.dto.OperationResponse;
import com.example.drive.job.dto.RenewAttemptResponse;
import com.example.drive.job.dto.StartOperationResponse;
import com.example.drive.job.dto.StartOutcome;
import com.example.drive.job.repository.ArtifactRepository;
import com.example.drive.job.repository.ExecutionAttemptRepository;
import com.example.drive.job.repository.OperationRepository;
import com.example.drive.worker.WorkerNotFoundException;
import com.example.drive.worker.domain.Worker;
import com.example.drive.worker.domain.WorkerStatus;
import com.example.drive.worker.repository.WorkerRepository;

import jakarta.persistence.EntityManager;

@Service
public class InternalOperationService {

	private static final Logger log = LoggerFactory.getLogger(InternalOperationService.class);

	private final EntityManager entityManager;
	private final OperationRepository operationRepository;
	private final ArtifactRepository artifactRepository;
	private final ExecutionAttemptRepository attemptRepository;
	private final WorkerRepository workerRepository;
	private final OperationLeaseProperties leaseProperties;
	private final Clock clock;

	public InternalOperationService(
			EntityManager entityManager,
			OperationRepository operationRepository,
			ArtifactRepository artifactRepository,
			ExecutionAttemptRepository attemptRepository,
			WorkerRepository workerRepository,
			OperationLeaseProperties leaseProperties,
			Clock clock
	) {
		this.entityManager = entityManager;
		this.operationRepository = operationRepository;
		this.artifactRepository = artifactRepository;
		this.attemptRepository = attemptRepository;
		this.workerRepository = workerRepository;
		this.leaseProperties = leaseProperties;
		this.clock = clock;
	}

	@Transactional
	public StartOperationResponse start(UUID operationId, String workerId, UUID assignmentId) {
		Worker worker = requireEligibleWorker(workerId);
		lockJobForOperation(operationId);
		worker = requireEligibleWorker(workerId);
		Instant now = nowUtc();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		requireSupportsOperation(worker, operation);
		return switch (operation.getStatus()) {
			case ASSIGNED -> {
				requireCurrentAssignment(operation, worker.getId(), assignmentId);
				ExecutionAttempt attempt = createRunningAttempt(operation, worker.getId(), now);
				operation.markRunning(now);
				operation.attachRunningAttempt(attempt.getId());
				operation.getJob().refreshStatusFromOperations(now);
				log.info(
						"event=attempt_started operationId={} attemptId={} attemptNumber={} workerId={} leaseExpiresAt={}",
						operation.getId(),
						attempt.getId(),
						attempt.getAttemptNumber(),
						worker.getId(),
						attempt.getLeaseExpiresAt()
				);
				yield StartOperationResponse.started(
						operation,
						attempt.getId(),
						worker.getId(),
						attempt.getLeaseExpiresAt()
				);
			}
			case RUNNING -> StartOperationResponse.from(StartOutcome.ALREADY_RUNNING, operation);
			case COMPLETED, FAILED, CANCELLED, CANCEL_REQUESTED ->
					StartOperationResponse.from(StartOutcome.ALREADY_TERMINAL, operation);
			case QUEUED -> StartOperationResponse.from(StartOutcome.INVALID_STATE, operation);
		};
	}

	@Transactional
	public Optional<ClaimedOperationResponse> claimNextExecutableOperation(String workerId) {
		Worker worker = requireEligibleWorker(workerId);
		Optional<UUID> lockedId = lockNextClaimableId();
		if (lockedId.isEmpty()) {
			return Optional.empty();
		}

		lockJobForOperation(lockedId.get());
		Instant now = nowUtc();
		Operation operation = operationRepository.findByIdWithJobAndOperations(lockedId.get())
				.orElseThrow(() -> new OperationNotFoundException(lockedId.get()));
		requireSupportsOperation(worker, operation);
		if (operation.getStatus() != com.example.drive.job.domain.OperationStatus.QUEUED) {
			return Optional.empty();
		}
		ExecutionAttempt attempt = createRunningAttempt(operation, worker.getId(), now);
		operation.markRunning(now);
		operation.attachRunningAttempt(attempt.getId());
		operation.getJob().refreshStatusFromOperations(now);
		log.info(
				"event=attempt_started operationId={} attemptId={} attemptNumber={} workerId={} leaseExpiresAt={}",
				operation.getId(),
				attempt.getId(),
				attempt.getAttemptNumber(),
				worker.getId(),
				attempt.getLeaseExpiresAt()
		);
		return Optional.of(ClaimedOperationResponse.from(
				operation,
				now,
				attempt.getId(),
				worker.getId(),
				attempt.getLeaseExpiresAt()
		));
	}

	@Transactional
	public RenewAttemptResponse renew(UUID operationId, UUID attemptId, String workerId) {
		lockJobForOperation(operationId);
		Instant now = nowUtc();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		ExecutionAttempt attempt = attemptRepository.findByIdAndOperation_Id(attemptId, operationId)
				.orElseThrow(() -> new AttemptNotFoundException(attemptId));
		if (attempt.getStatus() != AttemptStatus.RUNNING
				|| !isRenewableOperation(operation)
				|| !attemptId.equals(operation.getCurrentAttemptId())
				|| !attempt.getWorkerId().equals(workerId)) {
			throw new StaleExecutionAttemptException(
					attemptId,
					"Attempt " + attemptId + " is not the current running owner for operation " + operationId
			);
		}
		Worker worker = workerRepository.findById(workerId)
				.orElseThrow(() -> new WorkerNotFoundException(workerId));
		if (worker.getStatus() != WorkerStatus.AVAILABLE) {
			throw new WorkerNotEligibleException("WORKER_UNAVAILABLE", "Worker is not AVAILABLE: " + workerId);
		}
		Instant leaseExpiresAt = now.plus(leaseProperties.getLeaseDuration());
		attempt.renewLease(leaseExpiresAt);
		boolean cancelRequested = operation.getStatus() == com.example.drive.job.domain.OperationStatus.CANCEL_REQUESTED;
		log.debug(
				"event=lease_renewed operationId={} attemptId={} workerId={} leaseExpiresAt={} cancelRequested={}",
				operationId,
				attemptId,
				workerId,
				leaseExpiresAt,
				cancelRequested
		);
		return new RenewAttemptResponse(
				attempt.getId(),
				worker.getId(),
				attempt.getStatus(),
				leaseExpiresAt,
				cancelRequested
		);
	}

	@Transactional
	public OperationResponse acknowledgeCancelled(UUID operationId, UUID attemptId, String workerId, Long runtimeMs) {
		lockJobForOperation(operationId);
		Instant now = nowUtc();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		ExecutionAttempt attempt = attemptRepository.findByIdAndOperation_Id(attemptId, operationId)
				.orElseThrow(() -> new AttemptNotFoundException(attemptId));
		if (operation.getStatus() == com.example.drive.job.domain.OperationStatus.CANCELLED
				&& attempt.getStatus() == AttemptStatus.CANCELLED
				&& attemptId.equals(operation.getCurrentAttemptId())
				&& attempt.getWorkerId().equals(workerId)) {
			return OperationResponse.from(operation);
		}
		if (attempt.getStatus() != AttemptStatus.RUNNING
				|| operation.getStatus() != com.example.drive.job.domain.OperationStatus.CANCEL_REQUESTED
				|| !attemptId.equals(operation.getCurrentAttemptId())
				|| !attempt.getWorkerId().equals(workerId)) {
			rejectStale(attemptId, operationId);
		}
		attempt.markCancelled(now, runtimeMs, null);
		operation.markCancelled(now);
		operation.getJob().refreshStatusFromOperations(now);
		log.info(
				"event=attempt_cancelled operationId={} attemptId={} workerId={} runtimeMs={}",
				operation.getId(),
				attempt.getId(),
				attempt.getWorkerId(),
				runtimeMs
		);
		return OperationResponse.from(operation);
	}

	@Transactional
	public OperationResponse complete(UUID operationId, CompleteOperationRequest request) {
		lockJobForOperation(operationId);
		Instant now = nowUtc();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		ExecutionAttempt attempt = requireAttemptForResult(operation, request.attemptId(), true);

		boolean changed = switch (operation.getType()) {
			case METADATA -> completeMetadata(operation, attempt, request, now);
			case THUMBNAIL -> completeArtifact(operation, attempt, request, now, ArtifactType.THUMBNAIL);
			case AUDIO_EXTRACTION -> completeArtifact(operation, attempt, request, now, ArtifactType.AUDIO);
			case TRANSCODE_1080P -> completeArtifact(operation, attempt, request, now, ArtifactType.TRANSCODE_1080P);
			case H264_TO_AV1 -> completeArtifact(operation, attempt, request, now, ArtifactType.H264_TO_AV1);
		};
		if (changed) {
			operation.getJob().refreshStatusFromOperations(now);
			log.info(
					"event=attempt_completed operationId={} attemptId={} workerId={} runtimeMs={}",
					operation.getId(),
					attempt.getId(),
					attempt.getWorkerId(),
					request.actualRuntimeMs()
			);
		}
		return OperationResponse.from(operation);
	}

	@Transactional
	public OperationResponse fail(UUID operationId, FailOperationRequest request) {
		lockJobForOperation(operationId);
		Instant now = nowUtc();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		ExecutionAttempt attempt = requireAttemptForResult(operation, request.attemptId(), false);
		boolean changed = false;
		if (attempt.getStatus() == AttemptStatus.RUNNING) {
			attempt.markFailed(now, request.actualRuntimeMs(), request.reason().trim());
			changed = operation.markFailed(now, request.actualRuntimeMs(), request.reason().trim());
		}
		if (changed) {
			operation.getJob().refreshStatusFromOperations(now);
			log.info(
					"event=attempt_failed operationId={} attemptId={} workerId={} reason={}",
					operation.getId(),
					attempt.getId(),
					attempt.getWorkerId(),
					request.reason().trim()
			);
		}
		return OperationResponse.from(operation);
	}

	@Transactional
	public int reclaimExpiredAttempts() {
		Instant now = nowUtc();
		List<UUID> expiredIds = attemptRepository.findExpiredRunningIds(AttemptStatus.RUNNING, now);
		int reclaimed = 0;
		for (UUID attemptId : expiredIds) {
			if (reclaimOne(attemptId, now)) {
				reclaimed++;
			}
		}
		return reclaimed;
	}

	private boolean reclaimOne(UUID attemptId, Instant now) {
		ExecutionAttempt unlocked = attemptRepository.findById(attemptId).orElse(null);
		if (unlocked == null) {
			return false;
		}
		UUID operationId = unlocked.getOperation().getId();
		lockJobForOperation(operationId);
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElse(null);
		if (operation == null) {
			return false;
		}
		ExecutionAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
		if (attempt == null
				|| attempt.getStatus() != AttemptStatus.RUNNING
				|| !attemptId.equals(operation.getCurrentAttemptId())
				|| !attempt.getLeaseExpiresAt().isBefore(now)) {
			return false;
		}
		if (operation.getStatus() == com.example.drive.job.domain.OperationStatus.CANCEL_REQUESTED) {
			attempt.markCancelled(now, null, "cancellation acknowledged by lease expiry");
			operation.markCancelled(now);
			operation.getJob().refreshStatusFromOperations(now);
			log.info(
					"event=attempt_cancelled_by_lease operationId={} attemptId={} workerId={}",
					operation.getId(),
					attempt.getId(),
					attempt.getWorkerId()
			);
			return true;
		}
		if (operation.getStatus() != com.example.drive.job.domain.OperationStatus.RUNNING) {
			return false;
		}
		Worker worker = workerRepository.findById(attempt.getWorkerId()).orElse(null);
		if (worker == null || worker.getStatus() != WorkerStatus.UNAVAILABLE) {
			return false;
		}

		attempt.markInterrupted(now);
		if (attempt.getAttemptNumber() >= leaseProperties.getMaxAttempts()) {
			operation.markFailed(now, null, "maximum execution attempts exceeded");
			operation.getJob().refreshStatusFromOperations(now);
			log.info(
					"event=attempt_interrupted operationId={} attemptId={} workerId={} attemptNumber={} terminal=max_attempts",
					operation.getId(),
					attempt.getId(),
					attempt.getWorkerId(),
					attempt.getAttemptNumber()
			);
			return true;
		}

		operation.markRequeued(now);
		clearDispatchOutbox(operation.getId());
		operation.getJob().refreshStatusFromOperations(now);
		log.info(
				"event=attempt_interrupted operationId={} attemptId={} workerId={} attemptNumber={}",
				operation.getId(),
				attempt.getId(),
				attempt.getWorkerId(),
				attempt.getAttemptNumber()
		);
		log.info(
				"event=operation_requeued operationId={} jobId={} nextAttemptNumber={}",
				operation.getId(),
				operation.getJob().getId(),
				attempt.getAttemptNumber() + 1
		);
		return true;
	}

	private ExecutionAttempt createRunningAttempt(Operation operation, String workerId, Instant now) {
		int nextNumber = attemptRepository.maxAttemptNumber(operation.getId()) + 1;
		ExecutionAttempt attempt = new ExecutionAttempt(
				UUID.randomUUID(),
				operation,
				workerId,
				nextNumber,
				now,
				now.plus(leaseProperties.getLeaseDuration())
		);
		return attemptRepository.save(attempt);
	}

	private ExecutionAttempt requireAttemptForResult(Operation operation, UUID attemptId, boolean completing) {
		ExecutionAttempt attempt = attemptRepository.findById(attemptId)
				.orElseThrow(() -> new AttemptNotFoundException(attemptId));
		if (!attempt.getOperation().getId().equals(operation.getId())) {
			rejectStale(attemptId, operation.getId());
		}
		boolean sameTerminal = completing
				? attempt.getStatus() == AttemptStatus.COMPLETED
				&& operation.getStatus() == com.example.drive.job.domain.OperationStatus.COMPLETED
				: attempt.getStatus() == AttemptStatus.FAILED
				&& operation.getStatus() == com.example.drive.job.domain.OperationStatus.FAILED;
		if (sameTerminal) {
			return attempt;
		}
		if (attempt.getStatus() != AttemptStatus.RUNNING
				|| operation.getStatus() != com.example.drive.job.domain.OperationStatus.RUNNING
				|| !attemptId.equals(operation.getCurrentAttemptId())) {
			rejectStale(attemptId, operation.getId());
		}
		return attempt;
	}

	private void rejectStale(UUID attemptId, UUID operationId) {
		log.info(
				"event=stale_attempt_result_rejected operationId={} attemptId={}",
				operationId,
				attemptId
		);
		throw new StaleExecutionAttemptException(
				attemptId,
				"Attempt " + attemptId + " is not the current execution owner for operation " + operationId
		);
	}

	private static boolean isRenewableOperation(Operation operation) {
		return operation.getStatus() == com.example.drive.job.domain.OperationStatus.RUNNING
				|| operation.getStatus() == com.example.drive.job.domain.OperationStatus.CANCEL_REQUESTED;
	}

	private Worker requireEligibleWorker(String rawWorkerId) {
		if (rawWorkerId == null || rawWorkerId.isBlank()) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "workerId is required");
		}
		String workerId = rawWorkerId.trim();
		Worker worker = workerRepository.findById(workerId)
				.orElseThrow(() -> new WorkerNotFoundException(workerId));
		if (worker.getStatus() != WorkerStatus.AVAILABLE) {
			throw new WorkerNotEligibleException("WORKER_UNAVAILABLE", "Worker is not AVAILABLE: " + workerId);
		}
		return worker;
	}

	private static void requireSupportsOperation(Worker worker, Operation operation) {
		if (!worker.getSupportedOperations().contains(operation.getType())) {
			throw new WorkerNotEligibleException(
					"WORKER_CAPABILITY_MISMATCH",
					"Worker " + worker.getId() + " does not advertise " + operation.getType()
			);
		}
	}

	private void requireCurrentAssignment(Operation operation, String workerId, UUID assignmentId) {
		if (operation.getCurrentAssignmentId() == null && operation.getAssignedWorkerId() == null) {
			return;
		}
		if (operation.getAssignedWorkerId() != null && !operation.getAssignedWorkerId().equals(workerId)) {
			rejectStaleAssignment(operation, workerId, assignmentId);
		}
		if (operation.getCurrentAssignmentId() != null
				&& !operation.getCurrentAssignmentId().equals(assignmentId)) {
			rejectStaleAssignment(operation, workerId, assignmentId);
		}
	}

	private void rejectStaleAssignment(Operation operation, String workerId, UUID assignmentId) {
		log.info(
				"event=stale_assignment_start_rejected operationId={} workerId={} assignmentId={} currentAssignmentId={} assignedWorkerId={}",
				operation.getId(),
				workerId,
				assignmentId,
				operation.getCurrentAssignmentId(),
				operation.getAssignedWorkerId()
		);
		throw new StaleAssignmentException(
				operation.getId(),
				"Assignment is not the current placement for operation " + operation.getId()
		);
	}

	private boolean completeMetadata(
			Operation operation,
			ExecutionAttempt attempt,
			CompleteOperationRequest request,
			Instant now
	) {
		if (request.metadata() == null) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "metadata is required for METADATA completion");
		}
		if (request.artifact() != null) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "artifact is not allowed for METADATA completion");
		}
		if (attempt.getStatus() != AttemptStatus.RUNNING) {
			return false;
		}
		attempt.markCompleted(now, request.actualRuntimeMs());
		return operation.markCompleted(now, request.actualRuntimeMs(), toResultMap(request.metadata()));
	}

	private boolean completeArtifact(
			Operation operation,
			ExecutionAttempt attempt,
			CompleteOperationRequest request,
			Instant now,
			ArtifactType artifactType
	) {
		if (request.artifact() == null) {
			throw new InvalidJobRequestException(
					"VALIDATION_FAILED",
					"artifact is required for " + operation.getType() + " completion"
			);
		}
		if (request.metadata() != null) {
			throw new InvalidJobRequestException(
					"VALIDATION_FAILED",
					"metadata is not allowed for " + operation.getType() + " completion"
			);
		}
		ArtifactCompletionDto artifact = request.artifact();
		validateArtifactObjectUri(artifact.objectUri());
		if (attempt.getStatus() != AttemptStatus.RUNNING) {
			return false;
		}
		attempt.markCompleted(now, request.actualRuntimeMs());
		boolean changed = operation.markCompleted(now, request.actualRuntimeMs(), null);
		if (changed && !artifactRepository.existsByOperationId(operation.getId())) {
			artifactRepository.save(new Artifact(
					UUID.randomUUID(),
					operation.getJob().getId(),
					operation.getId(),
					artifactType,
					artifact.objectUri().trim(),
					artifact.contentType().trim(),
					artifact.sizeBytes(),
					artifact.checksum().trim(),
					now
			));
		}
		return changed;
	}

	private void clearDispatchOutbox(UUID operationId) {
		entityManager.createNativeQuery("delete from dispatch_outbox where operation_id = :id")
				.setParameter("id", operationId)
				.executeUpdate();
	}

	private Instant nowUtc() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
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

	private Optional<UUID> lockNextClaimableId() {
		@SuppressWarnings("unchecked")
		List<Object> rows = entityManager.createNativeQuery("""
				SELECT o.id
				FROM operations o
				JOIN jobs j ON j.id = o.job_id
				WHERE o.status = 'QUEUED'
				  AND o.operation_type IN ('METADATA', 'THUMBNAIL', 'AUDIO_EXTRACTION', 'TRANSCODE_1080P', 'H264_TO_AV1')
				  AND (
				    LOWER(j.input_uri) LIKE 'file:%'
				    OR LOWER(j.input_uri) LIKE 's3:%'
				  )
				ORDER BY o.queued_at ASC, o.operation_order ASC, o.id ASC
				FOR UPDATE OF o SKIP LOCKED
				LIMIT 1
				""").getResultList();
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(toUuid(rows.getFirst()));
	}

	private static void validateArtifactObjectUri(String raw) {
		URI uri;
		try {
			uri = URI.create(raw.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "artifact objectUri is not a valid URI");
		}
		if (!"s3".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "artifact objectUri must be s3://bucket/key");
		}
		String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
		if (path.isBlank()) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "artifact objectUri must include an object key");
		}
	}

	private static UUID toUuid(Object value) {
		if (value instanceof UUID uuid) {
			return uuid;
		}
		return UUID.fromString(value.toString());
	}

	private static Map<String, Object> toResultMap(MetadataResultDto result) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfPresent(map, "durationSeconds", result.durationSeconds());
		putIfPresent(map, "formatName", result.formatName());
		putIfPresent(map, "sizeBytes", result.sizeBytes());
		putIfPresent(map, "videoCodec", result.videoCodec());
		putIfPresent(map, "audioCodec", result.audioCodec());
		putIfPresent(map, "width", result.width());
		putIfPresent(map, "height", result.height());
		putIfPresent(map, "frameRate", result.frameRate());
		return map;
	}

	private static void putIfPresent(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}
}
