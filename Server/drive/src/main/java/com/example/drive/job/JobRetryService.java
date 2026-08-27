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
import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.dto.RetryJobResponse;
import com.example.drive.job.dto.RetryOperationResponse;
import com.example.drive.job.repository.ExecutionAttemptRepository;
import com.example.drive.job.repository.JobRepository;

import jakarta.persistence.EntityManager;

@Service
public class JobRetryService {

	private static final Logger log = LoggerFactory.getLogger(JobRetryService.class);

	private final EntityManager entityManager;
	private final JobRepository jobRepository;
	private final ExecutionAttemptRepository attemptRepository;
	private final Clock clock;

	public JobRetryService(
			EntityManager entityManager,
			JobRepository jobRepository,
			ExecutionAttemptRepository attemptRepository,
			Clock clock
	) {
		this.entityManager = entityManager;
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.clock = clock;
	}

	@Transactional
	public RetryOperationResponse retryOperation(UUID jobId, UUID operationId) {
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
		retryOne(operation, now);
		job.refreshStatusFromOperations(now);
		log.info(
				"event=operation_retry_queued jobId={} operationId={} jobStatus={} attemptCount={}",
				job.getId(),
				operation.getId(),
				job.getStatus(),
				attemptRepository.maxAttemptNumber(operation.getId())
		);
		return toResponse(job, operation);
	}

	@Transactional
	public RetryJobResponse retryJob(UUID jobId) {
		lockJob(jobId);
		Instant now = nowUtc();
		Job job = jobRepository.findByIdWithOperations(jobId)
				.orElseThrow(() -> new JobNotFoundException(jobId));
		List<Operation> failed = job.getOperations().stream()
				.filter(operation -> operation.getStatus() == OperationStatus.FAILED)
				.toList();
		if (failed.isEmpty()) {
			throw new NothingToRetryException(jobId);
		}
		for (Operation operation : failed) {
			retryOne(operation, now);
		}
		job.refreshStatusFromOperations(now);
		List<RetryOperationResponse> retried = failed.stream()
				.map(operation -> toResponse(job, operation))
				.toList();
		log.info(
				"event=job_retry_queued jobId={} jobStatus={} retried={}",
				job.getId(),
				job.getStatus(),
				retried.size()
		);
		return new RetryJobResponse(job.getId(), job.getStatus(), retried);
	}

	private void retryOne(Operation operation, Instant now) {
		if (operation.getStatus() != OperationStatus.FAILED) {
			throw new IllegalOperationStateException(operation.getId(), operation.retryRejectedMessage());
		}
		operation.markRetryQueued(now);
		clearDispatchOutbox(operation.getId());
	}

	private void clearDispatchOutbox(UUID operationId) {
		entityManager.createNativeQuery("delete from dispatch_outbox where operation_id = :id")
				.setParameter("id", operationId)
				.executeUpdate();
	}

	private RetryOperationResponse toResponse(Job job, Operation operation) {
		return new RetryOperationResponse(
				job.getId(),
				job.getStatus(),
				operation.getId(),
				operation.getStatus(),
				attemptRepository.maxAttemptNumber(operation.getId())
		);
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
}
