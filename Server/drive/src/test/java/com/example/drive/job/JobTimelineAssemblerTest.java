package com.example.drive.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.drive.job.domain.Artifact;
import com.example.drive.job.domain.ArtifactType;
import com.example.drive.job.domain.AttemptStatus;
import com.example.drive.job.domain.ExecutionAttempt;
import com.example.drive.job.domain.Job;
import com.example.drive.job.domain.JobPriority;
import com.example.drive.job.domain.JobStatus;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.domain.OperationType;
import com.example.drive.job.dto.JobTimelineResponse;
import com.example.drive.job.dto.OperationTimingResponse;
import com.example.drive.job.dto.TimelineEventResponse;
import com.example.drive.job.dto.TimelineEventType;
import com.example.drive.scheduler.domain.SchedulingDecision;

class JobTimelineAssemblerTest {

	private static final Instant T0 = Instant.parse("2026-08-27T20:00:00Z");
	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	private final JobTimelineAssembler assembler = new JobTimelineAssembler();

	@Test
	void completedJobIncludesLifecycleTimingsAndArtifact() {
		UUID jobId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID opId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		UUID attemptId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		UUID decisionId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		UUID artifactId = UUID.fromString("55555555-5555-5555-5555-555555555555");
		Instant queued = T0;
		Instant assigned = T0.plusMillis(240);
		Instant started = assigned.plusMillis(31);
		Instant ended = started.plusMillis(1842);

		Job job = new Job(jobId, UUID.randomUUID(), "s3://media-input/video.mp4", JobPriority.NORMAL, null, queued);
		job.attachTrace("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null);
		Operation operation = new Operation(opId, OperationType.THUMBNAIL, 0, queued);
		job.addOperation(operation);
		operation.markAssigned(assigned, "worker-b", decisionId);
		operation.markRunning(started);
		operation.attachRunningAttempt(attemptId);
		ExecutionAttempt attempt = new ExecutionAttempt(attemptId, operation, "worker-b", 1, started, started.plusSeconds(30));
		attempt.markCompleted(ended, 1842);
		operation.markCompleted(ended, 1842, null);
		job.refreshStatusFromOperations(ended);

		SchedulingDecision decision = new SchedulingDecision(decisionId, opId, "worker-b", "FIFO", "LEAST_LOADED", assigned);
		Artifact artifact = new Artifact(
				artifactId,
				jobId,
				opId,
				ArtifactType.THUMBNAIL,
				"s3://media-output/jobs/" + jobId + "/thumbnail.jpg",
				"image/jpeg",
				100L,
				SHA256,
				ended
		);

		JobTimelineResponse response = assembler.assemble(job, List.of(artifact), List.of(attempt), List.of(decision));

		assertThat(response.status()).isEqualTo(JobStatus.COMPLETED);
		assertThat(response.durationMs()).isEqualTo(240 + 31 + 1842);
		assertThat(response.artifactCount()).isEqualTo(1);
		assertThat(response.completedOperationCount()).isEqualTo(1);
		assertThat(response.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
		assertThat(types(response)).containsExactly(
				TimelineEventType.JOB_CREATED,
				TimelineEventType.OPERATION_QUEUED,
				TimelineEventType.OPERATION_ASSIGNED,
				TimelineEventType.OPERATION_STARTED,
				TimelineEventType.ARTIFACT_CREATED,
				TimelineEventType.OPERATION_COMPLETED,
				TimelineEventType.JOB_COMPLETED
		);
		assertThat(response.events()).extracting(TimelineEventResponse::timestamp).isSorted();
		assertThat(response.events().get(2).workerId()).isEqualTo("worker-b");
		assertThat(response.events().get(2).workerPolicy()).isEqualTo("LEAST_LOADED");
		assertThat(response.events().get(2).operationPolicy()).isEqualTo("FIFO");
		assertThat(response.events().get(4).artifactId()).isEqualTo(artifactId);
		assertThat(response.events().get(4).checksum()).isEqualTo(SHA256);
		assertThat(response.events().get(4).sizeBytes()).isEqualTo(100L);
		assertThat(response.events().get(4).message()).doesNotContain("s3://");
		assertThat(response.events()).noneMatch(event -> event.message() != null && event.message().contains("X-Amz-"));

		OperationTimingResponse timing = response.operations().get(0);
		assertThat(timing.queueWaitMs()).isEqualTo(240L);
		assertThat(timing.assignmentWaitMs()).isEqualTo(31L);
		assertThat(timing.executionRuntimeMs()).isEqualTo(1842L);
		assertThat(timing.totalOperationLatencyMs()).isEqualTo(240 + 31 + 1842);
		assertThat(timing.lastWorkerId()).isEqualTo("worker-b");
		assertThat(timing.attemptCount()).isEqualTo(1);
	}

	@Test
	void failedAttemptKeepsSafeReasonAndOmitsSecrets() {
		Instant assigned = T0.plusMillis(10);
		Instant started = assigned.plusMillis(5);
		Instant ended = started.plusMillis(50);
		Job job = queuedJob(OperationType.METADATA);
		Operation operation = job.getOperations().get(0);
		operation.markAssigned(assigned, "worker-a", UUID.randomUUID());
		operation.markRunning(started);
		ExecutionAttempt attempt = new ExecutionAttempt(
				UUID.randomUUID(),
				operation,
				"worker-a",
				1,
				started,
				started.plusSeconds(30)
		);
		String raw = """
				java.lang.IllegalStateException: FFmpeg exited non-zero
					at com.example.drive.job.InternalOperationService.fail(InternalOperationService.java:12)
				""";
		attempt.markFailed(ended, 50L, raw);
		operation.markFailed(ended, 50L, raw);
		job.refreshStatusFromOperations(ended);

		JobTimelineResponse response = assembler.assemble(
				job,
				List.of(),
				List.of(attempt),
				List.of(new SchedulingDecision(UUID.randomUUID(), operation.getId(), "worker-a", "FIFO", "LEXICOGRAPHIC", assigned))
		);

		assertThat(response.status()).isEqualTo(JobStatus.FAILED);
		TimelineEventResponse failed = response.events().stream()
				.filter(event -> event.type() == TimelineEventType.OPERATION_FAILED)
				.findFirst()
				.orElseThrow();
		assertThat(failed.failureReason()).isEqualTo("FFmpeg exited non-zero");
		assertThat(failed.message()).doesNotContain("at com.example");
		assertThat(failed.workerId()).isEqualTo("worker-a");
		assertThat(failed.runtimeMs()).isEqualTo(50L);
		assertThat(failed.outcome()).isEqualTo(AttemptStatus.FAILED);
		assertThat(types(response)).contains(TimelineEventType.JOB_FAILED);
	}

	@Test
	void retryPreservesBothAttemptsInChronologicalOrder() {
		Instant tAssign1 = T0.plusMillis(10);
		Instant tStart1 = tAssign1.plusMillis(10);
		Instant tFail = tStart1.plusMillis(20);
		Instant tRetry = tFail.plusMillis(5);
		Instant tAssign2 = tRetry.plusMillis(15);
		Instant tStart2 = tAssign2.plusMillis(8);
		Instant tComplete = tStart2.plusMillis(40);

		Job job = queuedJob(OperationType.METADATA);
		Operation operation = job.getOperations().get(0);
		UUID assignment1 = UUID.randomUUID();
		operation.markAssigned(tAssign1, "worker-a", assignment1);
		operation.markRunning(tStart1);
		ExecutionAttempt attempt1 = new ExecutionAttempt(UUID.randomUUID(), operation, "worker-a", 1, tStart1, tStart1.plusSeconds(30));
		attempt1.markFailed(tFail, 20L, "mpeg4 is not h264");
		operation.markFailed(tFail, 20L, "mpeg4 is not h264");
		operation.markRetryQueued(tRetry);
		UUID assignment2 = UUID.randomUUID();
		operation.markAssigned(tAssign2, "worker-b", assignment2);
		operation.markRunning(tStart2);
		ExecutionAttempt attempt2 = new ExecutionAttempt(UUID.randomUUID(), operation, "worker-b", 2, tStart2, tStart2.plusSeconds(30));
		attempt2.markCompleted(tComplete, 40L);
		operation.markCompleted(tComplete, 40L, null);
		job.refreshStatusFromOperations(tComplete);

		JobTimelineResponse response = assembler.assemble(
				job,
				List.of(),
				List.of(attempt1, attempt2),
				List.of(
						new SchedulingDecision(assignment1, operation.getId(), "worker-a", "FIFO", "LEXICOGRAPHIC", tAssign1),
						new SchedulingDecision(assignment2, operation.getId(), "worker-b", "FIFO", "LEAST_LOADED", tAssign2)
				)
		);

		assertThat(types(response)).containsSubsequence(
				TimelineEventType.OPERATION_STARTED,
				TimelineEventType.OPERATION_FAILED,
				TimelineEventType.OPERATION_RETRIED,
				TimelineEventType.OPERATION_ASSIGNED,
				TimelineEventType.OPERATION_STARTED,
				TimelineEventType.OPERATION_COMPLETED
		);
		List<TimelineEventResponse> attempts = response.events().stream()
				.filter(event -> event.type() == TimelineEventType.OPERATION_STARTED)
				.toList();
		assertThat(attempts).extracting(TimelineEventResponse::attemptNumber).containsExactly(1, 2);
		assertThat(attempts.get(0).workerId()).isEqualTo("worker-a");
		assertThat(attempts.get(1).workerId()).isEqualTo("worker-b");
		assertThat(response.events()).filteredOn(event -> event.type() == TimelineEventType.OPERATION_FAILED).hasSize(1);
		assertThat(response.operations().get(0).executionRuntimeMs()).isEqualTo(40L);
		assertThat(response.operations().get(0).attemptCount()).isEqualTo(2);
	}

	@Test
	void queuedCancelUsesCompletedAtAndDoesNotInventCancelRequested() {
		Job job = queuedJob(OperationType.METADATA);
		Operation operation = job.getOperations().get(0);
		Instant cancelledAt = T0.plusMillis(25);
		operation.markCancelled(cancelledAt);
		job.refreshStatusFromOperations(cancelledAt);

		JobTimelineResponse response = assembler.assemble(job, List.of(), List.of(), List.of());

		assertThat(response.status()).isEqualTo(JobStatus.CANCELLED);
		assertThat(types(response)).containsExactly(
				TimelineEventType.JOB_CREATED,
				TimelineEventType.OPERATION_QUEUED,
				TimelineEventType.OPERATION_CANCELLED,
				TimelineEventType.JOB_CANCELLED
		);
		assertThat(response.events().get(2).timestamp()).isEqualTo(cancelledAt);
		assertThat(response.events().get(2).attemptId()).isNull();
	}

	@Test
	void multiOperationEventsAreGloballyChronologicalNotGroupedByOperation() {
		Instant tAssignA = T0.plusMillis(10);
		Instant tAssignB = T0.plusMillis(20);
		Instant tStartA = T0.plusMillis(30);
		Instant tStartB = T0.plusMillis(40);
		Instant tCompleteB = T0.plusMillis(50);
		Instant tCompleteA = T0.plusMillis(90);

		UUID jobId = UUID.randomUUID();
		Job job = new Job(jobId, UUID.randomUUID(), "s3://media-input/video.mp4", JobPriority.NORMAL, null, T0);
		Operation opA = new Operation(UUID.randomUUID(), OperationType.METADATA, 0, T0);
		Operation opB = new Operation(UUID.randomUUID(), OperationType.THUMBNAIL, 1, T0);
		job.addOperation(opA);
		job.addOperation(opB);

		opA.markAssigned(tAssignA, "worker-a", UUID.randomUUID());
		opB.markAssigned(tAssignB, "worker-a", UUID.randomUUID());
		opA.markRunning(tStartA);
		opB.markRunning(tStartB);
		ExecutionAttempt attemptB = new ExecutionAttempt(UUID.randomUUID(), opB, "worker-a", 1, tStartB, tStartB.plusSeconds(30));
		attemptB.markCompleted(tCompleteB, 10L);
		opB.markCompleted(tCompleteB, 10L, null);
		ExecutionAttempt attemptA = new ExecutionAttempt(UUID.randomUUID(), opA, "worker-a", 1, tStartA, tStartA.plusSeconds(30));
		attemptA.markCompleted(tCompleteA, 60L);
		opA.markCompleted(tCompleteA, 60L, null);
		job.refreshStatusFromOperations(tCompleteA);

		JobTimelineResponse response = assembler.assemble(
				job,
				List.of(),
				List.of(attemptA, attemptB),
				List.of(
						new SchedulingDecision(UUID.randomUUID(), opA.getId(), "worker-a", "FIFO", "LEXICOGRAPHIC", tAssignA),
						new SchedulingDecision(UUID.randomUUID(), opB.getId(), "worker-a", "FIFO", "LEXICOGRAPHIC", tAssignB)
				)
		);

		List<TimelineEventResponse> execution = response.events().stream()
				.filter(event -> event.type() == TimelineEventType.OPERATION_ASSIGNED
						|| event.type() == TimelineEventType.OPERATION_STARTED
						|| event.type() == TimelineEventType.OPERATION_COMPLETED)
				.toList();
		assertThat(execution).extracting(TimelineEventResponse::operationType).containsExactly(
				OperationType.METADATA,
				OperationType.THUMBNAIL,
				OperationType.METADATA,
				OperationType.THUMBNAIL,
				OperationType.THUMBNAIL,
				OperationType.METADATA
		);
		assertThat(execution).extracting(TimelineEventResponse::timestamp).isSorted();
	}

	@Test
	void missingTimestampsOmitEventsAndDurations() {
		Job job = queuedJob(OperationType.METADATA);
		JobTimelineResponse response = assembler.assemble(job, List.of(), List.of(), List.of());

		assertThat(response.status()).isEqualTo(JobStatus.QUEUED);
		assertThat(response.durationMs()).isNull();
		assertThat(response.completedAt()).isNull();
		assertThat(types(response)).containsExactly(TimelineEventType.JOB_CREATED, TimelineEventType.OPERATION_QUEUED);
		OperationTimingResponse timing = response.operations().get(0);
		assertThat(timing.assignedAt()).isNull();
		assertThat(timing.startedAt()).isNull();
		assertThat(timing.completedAt()).isNull();
		assertThat(timing.queueWaitMs()).isNull();
		assertThat(timing.assignmentWaitMs()).isNull();
		assertThat(timing.executionRuntimeMs()).isNull();
		assertThat(timing.totalOperationLatencyMs()).isNull();
	}

	@Test
	void staleDecisionBeforeRequeueDoesNotInventQueueWait() {
		Job job = queuedJob(OperationType.METADATA);
		Operation operation = job.getOperations().get(0);
		Instant assigned = T0.plusMillis(5);
		Instant started = assigned.plusMillis(5);
		Instant failedAt = started.plusMillis(10);
		Instant retriedAt = failedAt.plusMillis(50);
		operation.markAssigned(assigned, "worker-a", UUID.randomUUID());
		operation.markRunning(started);
		ExecutionAttempt attempt = new ExecutionAttempt(UUID.randomUUID(), operation, "worker-a", 1, started, started.plusSeconds(30));
		attempt.markFailed(failedAt, 10L, "probe failed");
		operation.markFailed(failedAt, 10L, "probe failed");
		operation.markRetryQueued(retriedAt);
		job.refreshStatusFromOperations(retriedAt);

		JobTimelineResponse response = assembler.assemble(
				job,
				List.of(),
				List.of(attempt),
				List.of(new SchedulingDecision(UUID.randomUUID(), operation.getId(), "worker-a", "FIFO", "LEXICOGRAPHIC", assigned))
		);

		assertThat(response.operations().get(0).queueWaitMs()).isNull();
		assertThat(response.operations().get(0).executionRuntimeMs()).isNull();
		assertThat(types(response)).contains(TimelineEventType.OPERATION_RETRIED);
	}

	@Test
	void sameInstantUsesDeterministicPrecedence() {
		Job job = queuedJob(OperationType.METADATA);
		JobTimelineResponse response = assembler.assemble(job, List.of(), List.of(), List.of());
		assertThat(types(response)).containsExactly(TimelineEventType.JOB_CREATED, TimelineEventType.OPERATION_QUEUED);
		assertThat(response.events().get(0).timestamp()).isEqualTo(response.events().get(1).timestamp());
	}

	@Test
	void invalidTraceparentIsOmitted() {
		assertThat(JobTimelineAssembler.traceId(null)).isNull();
		assertThat(JobTimelineAssembler.traceId("not-a-trace")).isNull();
		assertThat(JobTimelineAssembler.traceId("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
				.isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
	}

	private static Job queuedJob(OperationType type) {
		Job job = new Job(UUID.randomUUID(), UUID.randomUUID(), "s3://media-input/video.mp4", JobPriority.NORMAL, null, T0);
		job.addOperation(new Operation(UUID.randomUUID(), type, 0, T0));
		return job;
	}

	private static List<TimelineEventType> types(JobTimelineResponse response) {
		return response.events().stream().map(TimelineEventResponse::type).toList();
	}
}
