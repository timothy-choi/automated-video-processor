package com.example.drive.job;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.dto.ArtifactResponse;
import com.example.drive.job.dto.AttemptResponse;
import com.example.drive.job.dto.CreateJobRequest;
import com.example.drive.job.dto.CreateOperationRequest;
import com.example.drive.job.dto.JobArtifactsResponse;
import com.example.drive.job.dto.JobListResponse;
import com.example.drive.job.dto.JobOperationsResponse;
import com.example.drive.job.dto.JobResponse;
import com.example.drive.job.dto.JobSummaryResponse;
import com.example.drive.job.dto.OperationAttemptsResponse;
import com.example.drive.job.repository.ArtifactRepository;
import com.example.drive.job.repository.ExecutionAttemptRepository;
import com.example.drive.job.repository.JobIdCount;
import com.example.drive.job.repository.JobRepository;
import com.example.drive.job.repository.OperationRepository;

@Service
public class JobService {

	private final JobRepository jobRepository;
	private final OperationRepository operationRepository;
	private final ArtifactRepository artifactRepository;
	private final ExecutionAttemptRepository attemptRepository;
	private final Clock clock;

	public JobService(
			JobRepository jobRepository,
			OperationRepository operationRepository,
			ArtifactRepository artifactRepository,
			ExecutionAttemptRepository attemptRepository,
			Clock clock
	) {
		this.jobRepository = jobRepository;
		this.operationRepository = operationRepository;
		this.artifactRepository = artifactRepository;
		this.attemptRepository = attemptRepository;
		this.clock = clock;
	}

	@Transactional
	public JobResponse createJob(CreateJobRequest request) {
		Instant now = clock.instant();
		String inputUri = validateInputUri(request.inputUri());
		validateDeadline(request.deadline(), now);

		Job job = new Job(
				UUID.randomUUID(),
				inputUri,
				request.priorityOrDefault(),
				request.deadline(),
				now
		);

		int order = 0;
		for (CreateOperationRequest operationRequest : request.operations()) {
			job.addOperation(new Operation(UUID.randomUUID(), operationRequest.type(), order, now));
			order++;
		}

		Job saved = jobRepository.save(job);
		return JobResponse.from(saved, 0L);
	}

	@Transactional(readOnly = true)
	public JobListResponse listJobs(JobListQuery query) {
		Page<Job> page = jobRepository.findAll(JobSpecifications.matching(query), query.toPageable());
		List<Job> jobs = page.getContent();
		List<UUID> ids = jobs.stream().map(Job::getId).toList();
		Map<UUID, Long> operationCounts = ids.isEmpty()
				? Map.of()
				: toCountMap(operationRepository.countGroupedByJobId(ids));
		Map<UUID, Long> artifactCounts = ids.isEmpty()
				? Map.of()
				: toCountMap(artifactRepository.countGroupedByJobId(ids));
		List<JobSummaryResponse> items = jobs.stream()
				.map(job -> JobSummaryResponse.from(
						job,
						operationCounts.getOrDefault(job.getId(), 0L),
						artifactCounts.getOrDefault(job.getId(), 0L)
				))
				.toList();
		return new JobListResponse(
				items,
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages()
		);
	}

	@Transactional(readOnly = true)
	public JobResponse getJob(UUID jobId) {
		Job job = jobRepository.findByIdWithOperations(jobId)
				.orElseThrow(() -> new JobNotFoundException(jobId));
		return JobResponse.from(job, artifactRepository.countByJobId(jobId));
	}

	@Transactional(readOnly = true)
	public JobOperationsResponse getOperations(UUID jobId) {
		if (!jobRepository.existsById(jobId)) {
			throw new JobNotFoundException(jobId);
		}
		return JobOperationsResponse.from(jobId, operationRepository.findByJob_IdOrderByOperationOrderAsc(jobId));
	}

	@Transactional(readOnly = true)
	public OperationAttemptsResponse getAttempts(UUID jobId, UUID operationId) {
		if (!jobRepository.existsById(jobId)) {
			throw new JobNotFoundException(jobId);
		}
		Operation operation = operationRepository.findById(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		if (!operation.getJob().getId().equals(jobId)) {
			throw new OperationNotFoundException(operationId);
		}
		return new OperationAttemptsResponse(
				jobId,
				operationId,
				attemptRepository.findByOperation_IdOrderByAttemptNumberAsc(operationId).stream()
						.map(AttemptResponse::from)
						.toList()
		);
	}

	@Transactional(readOnly = true)
	public JobArtifactsResponse getArtifacts(UUID jobId) {
		if (!jobRepository.existsById(jobId)) {
			throw new JobNotFoundException(jobId);
		}
		return JobArtifactsResponse.from(jobId, artifactRepository.findByJobIdOrderByCreatedAtAsc(jobId));
	}

	@Transactional(readOnly = true)
	public ArtifactResponse getArtifact(UUID jobId, UUID artifactId) {
		if (!jobRepository.existsById(jobId)) {
			throw new JobNotFoundException(jobId);
		}
		return ArtifactResponse.from(
				artifactRepository.findByIdAndJobId(artifactId, jobId)
						.orElseThrow(() -> new ArtifactNotFoundException(artifactId))
		);
	}

	private Map<UUID, Long> toCountMap(List<JobIdCount> rows) {
		Map<UUID, Long> counts = new HashMap<>();
		for (JobIdCount row : rows) {
			counts.put(row.getJobId(), row.getCount());
		}
		return counts;
	}

	private String validateInputUri(String rawInputUri) {
		String inputUri = rawInputUri.trim();
		URI uri;
		try {
			uri = URI.create(inputUri);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidJobRequestException("INPUT_URI_INVALID", "inputUri is not a valid URI");
		}
		if (uri.getScheme() == null || uri.getScheme().isBlank()) {
			throw new InvalidJobRequestException("INPUT_URI_INVALID", "inputUri must include a scheme");
		}
		return inputUri;
	}

	private void validateDeadline(Instant deadline, Instant now) {
		if (deadline != null && deadline.isBefore(now)) {
			throw new InvalidJobRequestException("DEADLINE_IN_THE_PAST", "deadline must not be in the past");
		}
	}
}
