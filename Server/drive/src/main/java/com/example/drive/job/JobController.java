package com.example.drive.job;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.job.dto.CreateJobRequest;
import com.example.drive.job.dto.JobArtifactsResponse;
import com.example.drive.job.dto.JobOperationsResponse;
import com.example.drive.job.dto.JobResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/jobs")
public class JobController {

	private final JobService jobService;

	public JobController(JobService jobService) {
		this.jobService = jobService;
	}

	@PostMapping
	public ResponseEntity<JobResponse> createJob(@Valid @RequestBody CreateJobRequest request) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobService.createJob(request));
	}

	@GetMapping("/{id}")
	public JobResponse getJob(@PathVariable("id") UUID id) {
		return jobService.getJob(id);
	}

	@GetMapping("/{id}/operations")
	public JobOperationsResponse getOperations(@PathVariable("id") UUID id) {
		return jobService.getOperations(id);
	}

	@GetMapping("/{id}/artifacts")
	public JobArtifactsResponse getArtifacts(@PathVariable("id") UUID id) {
		return jobService.getArtifacts(id);
	}
}
