package com.example.drive.job;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.dto.CreateJobRequest;
import com.example.drive.job.dto.CreateOperationRequest;
import com.example.drive.job.dto.JobOperationsResponse;
import com.example.drive.job.dto.JobResponse;
import com.example.drive.job.repository.JobRepository;
import com.example.drive.job.repository.OperationRepository;

@Service
public class JobService {

	private final JobRepository jobRepository;
	private final OperationRepository operationRepository;
	private final Clock clock;

	public JobService(JobRepository jobRepository, OperationRepository operationRepository, Clock clock) {
		this.jobRepository = jobRepository;
		this.operationRepository = operationRepository;
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
		return JobResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public JobResponse getJob(UUID jobId) {
		Job job = jobRepository.findByIdWithOperations(jobId)
				.orElseThrow(() -> new JobNotFoundException(jobId));
		return JobResponse.from(job);
	}

	@Transactional(readOnly = true)
	public JobOperationsResponse getOperations(UUID jobId) {
		if (!jobRepository.existsById(jobId)) {
			throw new JobNotFoundException(jobId);
		}
		return JobOperationsResponse.from(jobId, operationRepository.findByJob_IdOrderByOperationOrderAsc(jobId));
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
