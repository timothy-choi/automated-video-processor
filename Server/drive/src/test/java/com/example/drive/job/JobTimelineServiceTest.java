package com.example.drive.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.JobPriority;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationType;
import com.example.drive.job.repository.ArtifactRepository;
import com.example.drive.job.repository.ExecutionAttemptRepository;
import com.example.drive.job.repository.JobRepository;
import com.example.drive.scheduler.repository.SchedulingDecisionRepository;

@ExtendWith(MockitoExtension.class)
class JobTimelineServiceTest {

	@Mock
	private JobRepository jobRepository;

	@Mock
	private ArtifactRepository artifactRepository;

	@Mock
	private ExecutionAttemptRepository executionAttemptRepository;

	@Mock
	private SchedulingDecisionRepository schedulingDecisionRepository;

	private JobTimelineService service;

	@BeforeEach
	void setUp() {
		service = new JobTimelineService(
				jobRepository,
				artifactRepository,
				executionAttemptRepository,
				schedulingDecisionRepository,
				new JobTimelineAssembler()
		);
	}

	@Test
	void loadsOwnedJobWithFourBoundedQueries() {
		UUID jobId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		Job job = new Job(jobId, accountId, "s3://media-input/video.mp4", JobPriority.NORMAL, null, Instant.parse("2026-08-27T20:00:00Z"));
		Operation operation = new Operation(UUID.randomUUID(), OperationType.METADATA, 0, job.getCreatedAt());
		job.addOperation(operation);

		when(jobRepository.findByIdAndAccountIdWithOperations(jobId, accountId)).thenReturn(Optional.of(job));
		when(artifactRepository.findByJobIdOrderByCreatedAtAsc(jobId)).thenReturn(List.of());
		when(executionAttemptRepository.findByJobIdOrderByAttemptNumberAscIdAsc(jobId)).thenReturn(List.of());
		when(schedulingDecisionRepository.findByOperationIdInOrderByCreatedAtAscIdAsc(List.of(operation.getId())))
				.thenReturn(List.of());

		assertThat(service.getTimeline(jobId, accountId).jobId()).isEqualTo(jobId);

		verify(jobRepository, times(1)).findByIdAndAccountIdWithOperations(jobId, accountId);
		verify(artifactRepository, times(1)).findByJobIdOrderByCreatedAtAsc(jobId);
		verify(executionAttemptRepository, times(1)).findByJobIdOrderByAttemptNumberAscIdAsc(jobId);
		verify(schedulingDecisionRepository, times(1))
				.findByOperationIdInOrderByCreatedAtAscIdAsc(List.of(operation.getId()));
		verifyNoMoreInteractions(
				jobRepository,
				artifactRepository,
				executionAttemptRepository,
				schedulingDecisionRepository
		);
	}

	@Test
	void unknownOwnerThrowsJobNotFoundWithoutFollowUpQueries() {
		UUID jobId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		when(jobRepository.findByIdAndAccountIdWithOperations(jobId, accountId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getTimeline(jobId, accountId)).isInstanceOf(JobNotFoundException.class);

		verify(jobRepository, times(1)).findByIdAndAccountIdWithOperations(jobId, accountId);
		verifyNoMoreInteractions(jobRepository, artifactRepository, executionAttemptRepository, schedulingDecisionRepository);
	}
}
