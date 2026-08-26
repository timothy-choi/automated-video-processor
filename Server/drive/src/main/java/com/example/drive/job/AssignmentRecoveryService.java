package com.example.drive.job;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.repository.OperationRepository;
import com.example.drive.worker.domain.Worker;
import com.example.drive.worker.domain.WorkerStatus;
import com.example.drive.worker.repository.WorkerRepository;

import jakarta.persistence.EntityManager;

/**
 * Recovers Operations that were ASSIGNED but never started. This is separate
 * from lease recovery, which only applies after an ExecutionAttempt exists.
 */
@Service
public class AssignmentRecoveryService {

	private static final Logger log = LoggerFactory.getLogger(AssignmentRecoveryService.class);

	private final EntityManager entityManager;
	private final OperationRepository operationRepository;
	private final WorkerRepository workerRepository;
	private final OperationAssignmentProperties assignmentProperties;
	private final Clock clock;

	public AssignmentRecoveryService(
			EntityManager entityManager,
			OperationRepository operationRepository,
			WorkerRepository workerRepository,
			OperationAssignmentProperties assignmentProperties,
			Clock clock
	) {
		this.entityManager = entityManager;
		this.operationRepository = operationRepository;
		this.workerRepository = workerRepository;
		this.assignmentProperties = assignmentProperties;
		this.clock = clock;
	}

	@Transactional
	public int reclaimUnstartedAssignments() {
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Instant cutoff = now.minus(assignmentProperties.getStartTimeout());
		List<UUID> expiredIds = operationRepository.findExpiredUnstartedAssignedIds(cutoff);
		int reclaimed = 0;
		for (UUID operationId : expiredIds) {
			if (reclaimOne(operationId, now, cutoff)) {
				reclaimed++;
			}
		}
		return reclaimed;
	}

	private boolean reclaimOne(UUID operationId, Instant now, Instant cutoff) {
		lockJobForOperation(operationId);
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId).orElse(null);
		if (operation == null
				|| operation.getStatus() != OperationStatus.ASSIGNED
				|| operation.getCurrentAttemptId() != null
				|| operation.getAssignedAt() == null
				|| !operation.getAssignedAt().isBefore(cutoff)
				|| operation.getAssignedWorkerId() == null
				|| operation.getCurrentAssignmentId() == null) {
			return false;
		}
		Worker worker = workerRepository.findById(operation.getAssignedWorkerId()).orElse(null);
		if (worker != null && worker.getStatus() != WorkerStatus.UNAVAILABLE) {
			return false;
		}

		UUID assignmentId = operation.getCurrentAssignmentId();
		String workerId = operation.getAssignedWorkerId();
		log.info(
				"event=assignment_expired operationId={} assignmentId={} workerId={} assignedAt={}",
				operation.getId(),
				assignmentId,
				workerId,
				operation.getAssignedAt()
		);
		operation.markUnstartedAssignmentExpired(now);
		clearDispatchOutbox(operation.getId());
		operation.getJob().refreshStatusFromOperations(now);
		log.info(
				"event=operation_requeued_before_start operationId={} jobId={} assignmentId={} workerId={}",
				operation.getId(),
				operation.getJob().getId(),
				assignmentId,
				workerId
		);
		return true;
	}

	private void clearDispatchOutbox(UUID operationId) {
		entityManager.createNativeQuery("delete from dispatch_outbox where operation_id = :id")
				.setParameter("id", operationId)
				.executeUpdate();
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
