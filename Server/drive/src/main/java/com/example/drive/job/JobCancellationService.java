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

import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.JobStatus;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.dto.CancelOperationResponse;
import com.example.drive.job.dto.JobResponse;
import com.example.drive.job.repository.ArtifactRepository;
import com.example.drive.job.repository.JobRepository;

import jakarta.persistence.EntityManager;

@Service
public class JobCancellationService {

	private static final Logger log = LoggerFactory.getLogger(JobCancellationService.class);

	private final EntityManager entityManager;
	private final JobRepository jobRepository;
	private final ArtifactRepository artifactRepository;
	private final Clock clock;

	public JobCancellationService(
			EntityManager entityManager,
			JobRepository jobRepository,
			ArtifactRepository artifactRepository,
			Clock clock
	) {
		this.entityManager = entityManager;
		this.jobRepository = jobRepository;
		this.artifactRepository = artifactRepository;
		this.clock = clock;
	}

	@Transactional
	public JobResponse cancelJob(UUID jobId) {
		lockJob(jobId);
		Instant now = nowUtc();
		Job job = jobRepository.findByIdWithOperations(jobId)
				.orElseThrow(() -> new JobNotFoundException(jobId));
		if (job.getStatus() == JobStatus.CANCELLED) {
			return toResponse(job);
		}
		if (job.getStatus() == JobStatus.COMPLETED || job.getStatus() == JobStatus.FAILED) {
			throw new JobAlreadyTerminalException(jobId, job.getStatus());
		}
		for (Operation operation : job.getOperations()) {
			requestCancel(operation, now, true);
		}
		job.refreshStatusFromOperations(now);
		log.info("event=job_cancel_requested jobId={} jobStatus={}", job.getId(), job.getStatus());
		return toResponse(job);
	}

	@Transactional
	public CancelOperationResponse cancelOperation(UUID jobId, UUID operationId) {
		if (!jobRepository.existsById(jobId)) {
			throw new JobNotFoundException(jobId);
		}
		lockJob(jobId);
		Instant now = nowUtc();
		Job job = jobRepository.findByIdWithOperations(jobId)
				.orElseThrow(() -> new JobNotFoundException(jobId));
		Operation operation = job.getOperations().stream()
				.filter(candidate -> candidate.getId().equals(operationId))
				.findFirst()
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		requestCancel(operation, now, false);
		job.refreshStatusFromOperations(now);
		log.info(
				"event=operation_cancel_requested jobId={} operationId={} operationStatus={} jobStatus={}",
				job.getId(),
				operation.getId(),
				operation.getStatus(),
				job.getStatus()
		);
		return new CancelOperationResponse(
				job.getId(),
				job.getStatus(),
				operation.getId(),
				operation.getStatus()
		);
	}

	private void requestCancel(Operation operation, Instant now, boolean skipTerminalSiblings) {
		switch (operation.getStatus()) {
			case CANCELLED, CANCEL_REQUESTED -> {
			}
			case COMPLETED, FAILED -> {
				if (!skipTerminalSiblings) {
					throw new IllegalOperationStateException(
							operation.getId(),
							"Operation " + operation.getId() + " is already " + operation.getStatus()
									+ " and cannot be cancelled"
					);
				}
			}
			case QUEUED, ASSIGNED -> {
				OperationStatus previous = operation.getStatus();
				operation.markCancelled(now);
				clearDispatchOutbox(operation.getId());
				log.info(
						"event=operation_cancelled operationId={} previousStatus={} immediate=true",
						operation.getId(),
						previous
				);
			}
			case RUNNING -> {
				operation.markCancelRequested(now);
				log.info(
						"event=operation_cancel_requested operationId={} attemptId={} workerId={}",
						operation.getId(),
						operation.getCurrentAttemptId(),
						operation.getAssignedWorkerId()
				);
			}
		}
	}

	private void clearDispatchOutbox(UUID operationId) {
		entityManager.createNativeQuery("delete from dispatch_outbox where operation_id = :id")
				.setParameter("id", operationId)
				.executeUpdate();
	}

	private void lockJob(UUID jobId) {
		@SuppressWarnings("unchecked")
		List<Object> rows = entityManager.createNativeQuery("""
				SELECT id FROM jobs WHERE id = :id FOR UPDATE
				""")
				.setParameter("id", jobId)
				.getResultList();
		if (rows.isEmpty()) {
			throw new JobNotFoundException(jobId);
		}
	}

	private Instant nowUtc() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private JobResponse toResponse(Job job) {
		return JobResponse.from(job, artifactRepository.countByJobId(job.getId()));
	}
}
