package com.example.drive.job;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.domain.Artifact;
import com.example.drive.job.domain.ExecutionAttempt;
import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.dto.JobTimelineResponse;
import com.example.drive.job.repository.ArtifactRepository;
import com.example.drive.job.repository.ExecutionAttemptRepository;
import com.example.drive.job.repository.JobRepository;
import com.example.drive.scheduler.domain.SchedulingDecision;
import com.example.drive.scheduler.repository.SchedulingDecisionRepository;

/**
 * Read-only owner-scoped Job timeline. Assembled from persisted Job, Operation,
 * SchedulingDecision, ExecutionAttempt, and Artifact rows — never from Jaeger or
 * Prometheus.
 *
 * <p>Query budget is bounded (no per-event round trips):
 *
 * <ol>
 *   <li>owned Job + operations ({@code findByIdAndAccountIdWithOperations})</li>
 *   <li>artifacts for the Job</li>
 *   <li>execution attempts for the Job (join-fetch operation)</li>
 *   <li>scheduling decisions for the Job's operation ids (skipped when none)</li>
 * </ol>
 */
@Service
public class JobTimelineService {

	private final JobRepository jobRepository;
	private final ArtifactRepository artifactRepository;
	private final ExecutionAttemptRepository executionAttemptRepository;
	private final SchedulingDecisionRepository schedulingDecisionRepository;
	private final JobTimelineAssembler assembler;

	public JobTimelineService(
			JobRepository jobRepository,
			ArtifactRepository artifactRepository,
			ExecutionAttemptRepository executionAttemptRepository,
			SchedulingDecisionRepository schedulingDecisionRepository,
			JobTimelineAssembler assembler
	) {
		this.jobRepository = jobRepository;
		this.artifactRepository = artifactRepository;
		this.executionAttemptRepository = executionAttemptRepository;
		this.schedulingDecisionRepository = schedulingDecisionRepository;
		this.assembler = assembler;
	}

	@Transactional(readOnly = true)
	public JobTimelineResponse getTimeline(UUID jobId, UUID accountId) {
		Job job = jobRepository.findByIdAndAccountIdWithOperations(jobId, accountId)
				.orElseThrow(() -> new JobNotFoundException(jobId));

		List<Artifact> artifacts = artifactRepository.findByJobIdOrderByCreatedAtAsc(jobId);
		List<ExecutionAttempt> attempts =
				executionAttemptRepository.findByJobIdOrderByAttemptNumberAscIdAsc(jobId);
		List<UUID> operationIds = job.getOperations().stream().map(Operation::getId).toList();
		List<SchedulingDecision> decisions = operationIds.isEmpty()
				? List.of()
				: schedulingDecisionRepository.findByOperationIdInOrderByCreatedAtAscIdAsc(operationIds);

		return assembler.assemble(job, artifacts, attempts, decisions);
	}
}
