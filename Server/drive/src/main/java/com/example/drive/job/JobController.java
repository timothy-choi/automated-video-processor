package com.example.drive.job;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.job.dto.ArtifactDownloadResponse;
import com.example.drive.job.dto.ArtifactResponse;
import com.example.drive.job.dto.CancelOperationResponse;
import com.example.drive.job.dto.CreateJobRequest;
import com.example.drive.job.dto.JobArtifactsResponse;
import com.example.drive.job.dto.JobListResponse;
import com.example.drive.job.dto.JobOperationsResponse;
import com.example.drive.job.dto.JobResponse;
import com.example.drive.job.dto.JobTimelineResponse;
import com.example.drive.job.dto.OperationAttemptsResponse;
import com.example.drive.job.dto.RetryJobResponse;
import com.example.drive.job.dto.RetryOperationResponse;
import com.example.drive.security.CurrentAccount;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/jobs")
public class JobController {

	private final JobService jobService;
	private final JobTimelineService jobTimelineService;
	private final JobCancellationService jobCancellationService;
	private final JobRetryService jobRetryService;
	private final ArtifactAccessService artifactAccessService;
	private final CurrentAccount currentAccount;

	public JobController(
			JobService jobService,
			JobTimelineService jobTimelineService,
			JobCancellationService jobCancellationService,
			JobRetryService jobRetryService,
			ArtifactAccessService artifactAccessService,
			CurrentAccount currentAccount
	) {
		this.jobService = jobService;
		this.jobTimelineService = jobTimelineService;
		this.jobCancellationService = jobCancellationService;
		this.jobRetryService = jobRetryService;
		this.artifactAccessService = artifactAccessService;
		this.currentAccount = currentAccount;
	}

	@PostMapping
	public ResponseEntity<JobResponse> createJob(@Valid @RequestBody CreateJobRequest request) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobService.createJob(request, currentAccount.requireId()));
	}

	@GetMapping
	public JobListResponse listJobs(
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size,
			@RequestParam(name = "status", required = false) String status,
			@RequestParam(name = "operationType", required = false) String operationType,
			@RequestParam(name = "priority", required = false) String priority,
			@RequestParam(name = "createdAfter", required = false) Instant createdAfter,
			@RequestParam(name = "createdBefore", required = false) Instant createdBefore,
			@RequestParam(name = "sort", required = false) String sort,
			@RequestParam(name = "direction", required = false) String direction
	) {
		return jobService.listJobs(JobListQuery.parse(
				page,
				size,
				status,
				operationType,
				priority,
				createdAfter,
				createdBefore,
				sort,
				direction
		), currentAccount.requireId());
	}

	@PostMapping("/{id}/cancel")
	public JobResponse cancelJob(@PathVariable("id") UUID id) {
		return jobCancellationService.cancelJob(id, currentAccount.requireId());
	}

	@PostMapping("/{id}/operations/{operationId}/cancel")
	public CancelOperationResponse cancelOperation(
			@PathVariable("id") UUID id,
			@PathVariable("operationId") UUID operationId
	) {
		return jobCancellationService.cancelOperation(id, operationId, currentAccount.requireId());
	}

	@PostMapping("/{id}/retry")
	public RetryJobResponse retryJob(@PathVariable("id") UUID id) {
		return jobRetryService.retryJob(id, currentAccount.requireId());
	}

	@PostMapping("/{id}/operations/{operationId}/retry")
	public RetryOperationResponse retryOperation(
			@PathVariable("id") UUID id,
			@PathVariable("operationId") UUID operationId
	) {
		return jobRetryService.retryOperation(id, operationId, currentAccount.requireId());
	}

	@GetMapping("/{id}")
	public JobResponse getJob(@PathVariable("id") UUID id) {
		return jobService.getJob(id, currentAccount.requireId());
	}

	@GetMapping("/{id}/timeline")
	public JobTimelineResponse getTimeline(@PathVariable("id") UUID id) {
		return jobTimelineService.getTimeline(id, currentAccount.requireId());
	}

	@GetMapping("/{id}/operations")
	public JobOperationsResponse getOperations(@PathVariable("id") UUID id) {
		return jobService.getOperations(id, currentAccount.requireId());
	}

	@GetMapping("/{id}/operations/{operationId}/attempts")
	public OperationAttemptsResponse getAttempts(
			@PathVariable("id") UUID id,
			@PathVariable("operationId") UUID operationId
	) {
		return jobService.getAttempts(id, operationId, currentAccount.requireId());
	}

	@GetMapping("/{id}/artifacts")
	public JobArtifactsResponse getArtifacts(@PathVariable("id") UUID id) {
		return jobService.getArtifacts(id, currentAccount.requireId());
	}

	@GetMapping("/{id}/artifacts/{artifactId}")
	public ArtifactResponse getArtifact(
			@PathVariable("id") UUID id,
			@PathVariable("artifactId") UUID artifactId
	) {
		return jobService.getArtifact(id, artifactId, currentAccount.requireId());
	}

	@PostMapping("/{id}/artifacts/{artifactId}/download-url")
	public ArtifactDownloadResponse createArtifactDownloadUrl(
			@PathVariable("id") UUID id,
			@PathVariable("artifactId") UUID artifactId
	) {
		return artifactAccessService.createDownloadUrl(id, artifactId, currentAccount.requireId());
	}
}
