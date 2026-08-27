package com.example.drive.job;

import com.example.drive.support.AuthenticatedApiTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.job.dto.ArtifactCompletionDto;
import com.example.drive.job.dto.CompleteOperationRequest;
import com.example.drive.job.dto.FailOperationRequest;
import com.example.drive.job.dto.MetadataResultDto;
import com.example.drive.job.dto.StartOperationResponse;
import com.example.drive.scheduler.SchedulerService;
import com.example.drive.scheduler.dto.AssignOperationRequest;
import com.example.drive.support.DispatchServiceTest;
import com.example.drive.support.WorkerTestSupport;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DispatchServiceTest
class CancellationApiIntegrationTest extends AuthenticatedApiTest {

	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String ALL_OPS = """
			{"inputUri":"s3://media-input/video.mp4","operations":[
			  {"type":"METADATA"},
			  {"type":"THUMBNAIL"},
			  {"type":"AUDIO_EXTRACTION"},
			  {"type":"TRANSCODE_1080P"},
			  {"type":"H264_TO_AV1"}
			]}
			""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private InternalOperationService internalOperationService;

	@Autowired
	private JobCancellationService jobCancellationService;

	@Autowired
	private SchedulerService schedulerService;

	@Autowired
	private Clock clock;

	@BeforeEach
	void clearTables() throws Exception {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
		jdbcTemplate.update("delete from scheduling_decisions");
		jdbcTemplate.update("delete from dispatch_outbox");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
		jdbcTemplate.execute("delete from worker_supported_codecs");
		jdbcTemplate.execute("delete from worker_supported_operations");
		jdbcTemplate.execute("delete from workers");
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P", "H264_TO_AV1");
		WorkerTestSupport.register(mockMvc, "worker-b", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P", "H264_TO_AV1");
	}

	@Test
	void unknownJobCancelReturns404() throws Exception {
		UUID missing = UUID.fromString("99999999-9999-9999-9999-999999999999");
		mockMvc.perform(authed(post("/jobs/" + missing + "/cancel")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
	}

	@Test
	void unknownOperationCancelReturns404() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID missing = UUID.fromString("99999999-9999-9999-9999-999999999999");
		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + missing + "/cancel")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
	}

	@Test
	void operationFromAnotherJobReturns404() throws Exception {
		UUID jobA = createJob("""
				{"inputUri":"s3://media-input/a.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID jobB = createJob("""
				{"inputUri":"s3://media-input/b.mp4","operations":[{"type":"THUMBNAIL"}]}
				""");
		UUID opB = operationId(jobB, "THUMBNAIL");
		mockMvc.perform(authed(post("/jobs/" + jobA + "/operations/" + opB + "/cancel")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
	}

	@Test
	void cancelQueuedOperationIsImmediateAndNotSchedulable() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID operationId = operationId(jobId, "METADATA");
		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + operationId + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(jobId.toString()))
				.andExpect(jsonPath("$.jobStatus").value("CANCELLED"))
				.andExpect(jsonPath("$.operationId").value(operationId.toString()))
				.andExpect(jsonPath("$.operationStatus").value("CANCELLED"));
		assertThat(attemptCount(operationId)).isZero();
		assertThat(outboxCount(operationId)).isZero();
		assertThat(decisionCount(operationId)).isZero();
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations.length()").value(0));
		mockMvc.perform(authed(get("/jobs/" + jobId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.operations[0].status").value("CANCELLED"));
		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + operationId + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationStatus").value("CANCELLED"));
	}

	@Test
	void cancelJobCancelsAllQueuedOperations() throws Exception {
		UUID jobId = createJob(ALL_OPS);
		mockMvc.perform(authed(post("/jobs/" + jobId + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(jobId.toString()))
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from operations where job_id = ? and status = 'CANCELLED'",
				Integer.class,
				jobId
		)).isEqualTo(5);
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from execution_attempts",
				Integer.class
		)).isZero();
		mockMvc.perform(authed(post("/jobs/" + jobId + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void cancelAssignedOperationClearsPlacementAndRejectsStaleStart() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID operationId = operationId(jobId, "METADATA");
		schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		UUID assignmentId = currentAssignmentId(operationId);
		assertThat(operationStatus(operationId)).isEqualTo("ASSIGNED");
		assertThat(outboxCount(operationId)).isEqualTo(1);

		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + operationId + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationStatus").value("CANCELLED"))
				.andExpect(jsonPath("$.jobStatus").value("CANCELLED"));
		assertThat(currentAssignmentId(operationId)).isNull();
		assertThat(outboxCount(operationId)).isZero();
		assertThat(attemptCount(operationId)).isZero();

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-a", assignmentId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ALREADY_TERMINAL"))
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(attemptCount(operationId)).isZero();
	}

	@Test
	void cancelRunningOperationRequestsCancelThenAckFinalizes() throws Exception {
		Started started = assignAndStart("METADATA");
		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationStatus").value("CANCEL_REQUESTED"))
				.andExpect(jsonPath("$.jobStatus").value("CANCEL_REQUESTED"));
		assertThat(attemptStatus(started.attemptId())).isEqualTo("RUNNING");

		mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/renew"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cancelRequested").value(true))
				.andExpect(jsonPath("$.status").value("RUNNING"));

		mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/cancelled"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"workerId\":\"worker-a\",\"actualRuntimeMs\":15}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(attemptStatus(started.attemptId())).isEqualTo("CANCELLED");
		assertThat(jobStatus(started.jobId())).isEqualTo("CANCELLED");
		assertThat(artifactCount(started.operationId())).isZero();

		mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/cancelled"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"workerId\":\"worker-a\",\"actualRuntimeMs\":15}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		mockMvc.perform(authed(get("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/attempts")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.attempts[0].status").value("CANCELLED"))
				.andExpect(jsonPath("$.attempts[0].id").value(started.attemptId().toString()));
	}

	@Test
	void completedJobCannotBeCancelled() throws Exception {
		Started started = assignAndStart("METADATA");
		completeMetadata(started.operationId(), started.attemptId());
		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/cancel")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("JOB_ALREADY_COMPLETED"));
		assertThat(jobStatus(started.jobId())).isEqualTo("COMPLETED");
		assertThat(operationStatus(started.operationId())).isEqualTo("COMPLETED");
		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/cancel")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"));
	}

	@Test
	void failedJobCannotBeRewrittenAsCancelled() throws Exception {
		Started started = assignAndStart("METADATA");
		internalOperationService.fail(
				started.operationId(),
				new FailOperationRequest(started.attemptId(), 4L, "probe failed")
		);
		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/cancel")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("JOB_ALREADY_FAILED"));
		assertThat(jobStatus(started.jobId())).isEqualTo("FAILED");
		assertThat(operationStatus(started.operationId())).isEqualTo("FAILED");
	}

	@Test
	void mixedCompletedAndCancelledJobIsCancelledAndKeepsArtifacts() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[
				  {"type":"METADATA"},
				  {"type":"THUMBNAIL"},
				  {"type":"H264_TO_AV1"}
				]}
				""");
		UUID metadataId = operationId(jobId, "METADATA");
		UUID thumbnailId = operationId(jobId, "THUMBNAIL");
		UUID av1Id = operationId(jobId, "H264_TO_AV1");
		UUID metadataAttempt = assignAndStart(metadataId).attemptId();
		completeMetadata(metadataId, metadataAttempt);
		UUID thumbnailAttempt = assignAndStart(thumbnailId).attemptId();
		completeThumbnail(jobId, thumbnailId, thumbnailAttempt);

		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + av1Id + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationStatus").value("CANCELLED"))
				.andExpect(jsonPath("$.jobStatus").value("CANCELLED"));
		mockMvc.perform(authed(get("/jobs/" + jobId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(operationStatus(metadataId)).isEqualTo("COMPLETED");
		assertThat(operationStatus(thumbnailId)).isEqualTo("COMPLETED");
		assertThat(artifactCount(thumbnailId)).isEqualTo(1);
		mockMvc.perform(authed(get("/jobs/" + jobId + "/artifacts")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(1));
	}

	@Test
	void failedPlusCancelledOperationsKeepJobFailed() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[
				  {"type":"METADATA"},
				  {"type":"THUMBNAIL"}
				]}
				""");
		UUID metadataId = operationId(jobId, "METADATA");
		UUID thumbnailId = operationId(jobId, "THUMBNAIL");
		UUID attempt = assignAndStart(metadataId).attemptId();
		internalOperationService.fail(metadataId, new FailOperationRequest(attempt, 3L, "boom"));
		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + thumbnailId + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationStatus").value("CANCELLED"))
				.andExpect(jsonPath("$.jobStatus").value("FAILED"));
		assertThat(operationStatus(metadataId)).isEqualTo("FAILED");
		assertThat(jobStatus(jobId)).isEqualTo("FAILED");
	}

	@Test
	void staleCompleteAfterCancelIsRejectedAndCreatesNoArtifact() throws Exception {
		Started started = assignAndStart("THUMBNAIL");
		jobCancellationService.cancelOperation(started.jobId(), started.operationId(), account.accountId());
		mockMvc.perform(post("/internal/operations/" + started.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(thumbnailJson(started.jobId(), started.operationId(), started.attemptId())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		assertThat(operationStatus(started.operationId())).isEqualTo("CANCEL_REQUESTED");
		assertThat(artifactCount(started.operationId())).isZero();
		acknowledgeCancelled(started);
		assertThat(operationStatus(started.operationId())).isEqualTo("CANCELLED");
		assertThat(artifactCount(started.operationId())).isZero();
	}

	@Test
	void staleFailureAfterCancelIsRejected() throws Exception {
		Started started = assignAndStart("METADATA");
		jobCancellationService.cancelOperation(started.jobId(), started.operationId(), account.accountId());
		mockMvc.perform(post("/internal/operations/" + started.operationId() + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"attemptId":"%s","reason":"late fail"}
								""".formatted(started.attemptId())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		assertThat(operationStatus(started.operationId())).isEqualTo("CANCEL_REQUESTED");
		acknowledgeCancelled(started);
		assertThat(operationStatus(started.operationId())).isEqualTo("CANCELLED");
	}

	@Test
	void staleCancellationAckIsRejected() throws Exception {
		Started started = assignAndStart("METADATA");
		completeMetadata(started.operationId(), started.attemptId());
		mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/cancelled"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"workerId\":\"worker-a\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		assertThat(operationStatus(started.operationId())).isEqualTo("COMPLETED");
	}

	@Test
	void wrongWorkerCancellationAckIsRejected() throws Exception {
		Started started = assignAndStart("METADATA");
		jobCancellationService.cancelOperation(started.jobId(), started.operationId(), account.accountId());
		mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/cancelled"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"workerId\":\"worker-b\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		assertThat(operationStatus(started.operationId())).isEqualTo("CANCEL_REQUESTED");
	}

	@Test
	void leaseExpiryAfterCancelRequestedFinalizesCancelledWithoutRequeue() {
		Started started = assignAndStart("H264_TO_AV1");
		jobCancellationService.cancelOperation(started.jobId(), started.operationId(), account.accountId());
		expireLease(started.attemptId());
		assertThat(internalOperationService.reclaimExpiredAttempts()).isEqualTo(1);
		assertThat(attemptStatus(started.attemptId())).isEqualTo("CANCELLED");
		assertThat(operationStatus(started.operationId())).isEqualTo("CANCELLED");
		assertThat(jobStatus(started.jobId())).isEqualTo("CANCELLED");
		assertThat(currentAttemptId(started.operationId())).isEqualTo(started.attemptId());
		mockMvcGetSnapshotEmpty();
	}

	@Test
	void workerDisappearanceDuringCancellationDoesNotRequeue() {
		Started started = assignAndStart("TRANSCODE_1080P");
		jobCancellationService.cancelOperation(started.jobId(), started.operationId(), account.accountId());
		expireLease(started.attemptId());
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-a'");
		assertThat(internalOperationService.reclaimExpiredAttempts()).isEqualTo(1);
		assertThat(operationStatus(started.operationId())).isEqualTo("CANCELLED");
		assertThat(attemptStatus(started.attemptId())).isEqualTo("CANCELLED");
		assertThat(jobStatus(started.jobId())).isEqualTo("CANCELLED");
	}

	@Test
	void snapshotExcludesCancelledAndCancelRequested() {
		UUID queued = createJob("""
				{"inputUri":"s3://media-input/queued.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID cancelledJob = createJob("""
				{"inputUri":"s3://media-input/cancelled.mp4","operations":[{"type":"THUMBNAIL"}]}
				""");
		jobCancellationService.cancelJob(cancelledJob, account.accountId());
		Started running = assignAndStart("AUDIO_EXTRACTION");
		jobCancellationService.cancelOperation(running.jobId(), running.operationId(), account.accountId());
		UUID stillQueued = operationId(queued, "METADATA");
		try {
			mockMvc.perform(get("/internal/scheduler/snapshot"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.operations.length()").value(1))
					.andExpect(jsonPath("$.operations[0].operationId").value(stillQueued.toString()));
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	@Test
	void cancelVersusStartIsSerialized() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID operationId = operationId(jobId, "METADATA");
		schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		UUID assignmentId = currentAssignmentId(operationId);
		AtomicReference<StartOperationResponse> startResult = new AtomicReference<>();
		runConcurrent(
				() -> jobCancellationService.cancelOperation(jobId, operationId, account.accountId()),
				() -> startResult.set(internalOperationService.start(operationId, "worker-a", assignmentId))
		);
		String status = operationStatus(operationId);
		assertThat(status).isIn("CANCELLED", "CANCEL_REQUESTED");
		if ("CANCELLED".equals(status)) {
			assertThat(attemptCount(operationId)).isZero();
			assertThat(startResult.get().outcome().name()).isEqualTo("ALREADY_TERMINAL");
		}
		else {
			assertThat(attemptCount(operationId)).isEqualTo(1);
			assertThat(startResult.get().outcome().name()).isIn("STARTED", "ALREADY_RUNNING");
		}
	}

	@Test
	void cancelVersusCompleteLeavesOneAuthoritativeTerminal() throws Exception {
		Started started = assignAndStart("METADATA");
		AtomicReference<Throwable> cancelErr = new AtomicReference<>();
		AtomicReference<Throwable> completeErr = new AtomicReference<>();
		runConcurrent(
				() -> {
					try {
						jobCancellationService.cancelOperation(started.jobId(), started.operationId(), account.accountId());
					}
					catch (RuntimeException ex) {
						cancelErr.set(ex);
					}
				},
				() -> {
					try {
						internalOperationService.complete(
								started.operationId(),
								metadataComplete(started.attemptId())
						);
					}
					catch (RuntimeException ex) {
						completeErr.set(ex);
					}
				}
		);
		String status = operationStatus(started.operationId());
		assertThat(status).isIn("COMPLETED", "CANCEL_REQUESTED");
		if ("COMPLETED".equals(status)) {
			assertThat(jobStatus(started.jobId())).isEqualTo("COMPLETED");
			assertThat(cancelErr.get()).isInstanceOf(IllegalOperationStateException.class);
		}
		else {
			assertThat(completeErr.get()).isInstanceOf(StaleExecutionAttemptException.class);
			Boolean hasResult = jdbcTemplate.queryForObject(
					"select result_json is not null from operations where id = ?",
					Boolean.class,
					started.operationId()
			);
			assertThat(hasResult).isFalse();
		}
	}

	@Test
	void cancelVersusSchedulerAssignNeverLeavesWorkSchedulable() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID operationId = operationId(jobId, "METADATA");
		AtomicReference<Throwable> assignErr = new AtomicReference<>();
		runConcurrent(
				() -> jobCancellationService.cancelJob(jobId, account.accountId()),
				() -> {
					try {
						schedulerService.assign(new AssignOperationRequest(
								operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
					}
					catch (RuntimeException ex) {
						assignErr.set(ex);
					}
				}
		);
		assertThat(operationStatus(operationId)).isEqualTo("CANCELLED");
		assertThat(jobStatus(jobId)).isEqualTo("CANCELLED");
		assertThat(outboxCount(operationId)).isZero();
		assertThat(attemptCount(operationId)).isZero();
		if (assignErr.get() != null) {
			assertThat(assignErr.get()).isInstanceOf(IllegalOperationStateException.class);
		}
	}

	@Test
	void cancellingOneOperationDoesNotDeleteSiblingArtifacts() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[
				  {"type":"THUMBNAIL"},
				  {"type":"AUDIO_EXTRACTION"}
				]}
				""");
		UUID thumbnailId = operationId(jobId, "THUMBNAIL");
		UUID audioId = operationId(jobId, "AUDIO_EXTRACTION");
		UUID thumbAttempt = assignAndStart(thumbnailId).attemptId();
		completeThumbnail(jobId, thumbnailId, thumbAttempt);
		jobCancellationService.cancelOperation(jobId, audioId, account.accountId());
		assertThat(artifactCount(thumbnailId)).isEqualTo(1);
		assertThat(artifactCount(audioId)).isZero();
		assertThat(jobStatus(jobId)).isEqualTo("CANCELLED");
	}

	private void mockMvcGetSnapshotEmpty() {
		try {
			mockMvc.perform(get("/internal/scheduler/snapshot"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.operations.length()").value(0));
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private Started assignAndStart(String type) {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"%s"}]}
				""".formatted(type));
		return assignAndStart(operationId(jobId, type));
	}

	private Started assignAndStart(UUID operationId) {
		String jobId = jdbcTemplate.queryForObject(
				"select job_id from operations where id = ?",
				String.class,
				operationId
		);
		schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		UUID assignmentId = currentAssignmentId(operationId);
		UUID attemptId = start(operationId, "worker-a", assignmentId);
		return new Started(UUID.fromString(jobId), operationId, attemptId, assignmentId);
	}

	private UUID start(UUID operationId, String workerId, UUID assignmentId) {
		try {
			MvcResult result = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
							.contentType(MediaType.APPLICATION_JSON)
							.content(WorkerTestSupport.startJson(workerId, assignmentId)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.outcome").value("STARTED"))
					.andReturn();
			return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.attemptId"));
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private void acknowledgeCancelled(Started started) throws Exception {
		mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/cancelled"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"workerId\":\"worker-a\",\"actualRuntimeMs\":11}"))
				.andExpect(status().isOk());
	}

	private UUID createJob(String json) {
		try {
			MvcResult result = mockMvc.perform(authed(post("/jobs"))
							.contentType(MediaType.APPLICATION_JSON)
							.content(json))
					.andExpect(status().isAccepted())
					.andReturn();
			return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private void completeMetadata(UUID operationId, UUID attemptId) {
		internalOperationService.complete(operationId, metadataComplete(attemptId));
	}

	private void completeThumbnail(UUID jobId, UUID operationId, UUID attemptId) {
		internalOperationService.complete(operationId, new CompleteOperationRequest(
				attemptId,
				20L,
				null,
				new ArtifactCompletionDto(
						"s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg",
						"image/jpeg",
						100L,
						SHA256
				)
		));
	}

	private static CompleteOperationRequest metadataComplete(UUID attemptId) {
		return new CompleteOperationRequest(
				attemptId,
				12L,
				new MetadataResultDto(2.0, "mp4", 100L, "h264", null, 320, 240, 30.0),
				null
		);
	}

	private static String thumbnailJson(UUID jobId, UUID operationId, UUID attemptId) {
		return """
				{
				  "attemptId": "%s",
				  "actualRuntimeMs": 20,
				  "artifact": {
				    "objectUri": "s3://media-output/jobs/%s/operations/%s/thumbnail.jpg",
				    "contentType": "image/jpeg",
				    "sizeBytes": 50,
				    "checksum": "%s"
				  }
				}
				""".formatted(attemptId, jobId, operationId, SHA256);
	}

	private void runConcurrent(Runnable first, Runnable second) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> a = pool.submit(() -> {
				ready.countDown();
				start.await();
				first.run();
				return null;
			});
			Future<?> b = pool.submit(() -> {
				ready.countDown();
				start.await();
				second.run();
				return null;
			});
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			a.get(15, TimeUnit.SECONDS);
			b.get(15, TimeUnit.SECONDS);
		}
	}

	private void expireLease(UUID attemptId) {
		jdbcTemplate.update(
				"update execution_attempts set lease_expires_at = ? where id = ?",
				Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
				attemptId
		);
	}

	private UUID operationId(UUID jobId, String type) {
		return UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ? and operation_type = ?",
				String.class,
				jobId,
				type
		));
	}

	private UUID currentAssignmentId(UUID operationId) {
		String raw = jdbcTemplate.queryForObject(
				"select current_assignment_id::text from operations where id = ?",
				String.class,
				operationId
		);
		return raw == null ? null : UUID.fromString(raw);
	}

	private UUID currentAttemptId(UUID operationId) {
		return jdbcTemplate.query(
				"select current_attempt_id from operations where id = ?",
				rs -> rs.next() ? rs.getObject("current_attempt_id", UUID.class) : null,
				operationId
		);
	}

	private String operationStatus(UUID operationId) {
		return jdbcTemplate.queryForObject("select status from operations where id = ?", String.class, operationId);
	}

	private String jobStatus(UUID jobId) {
		return jdbcTemplate.queryForObject("select status from jobs where id = ?", String.class, jobId);
	}

	private String attemptStatus(UUID attemptId) {
		return jdbcTemplate.queryForObject(
				"select status from execution_attempts where id = ?",
				String.class,
				attemptId
		);
	}

	private Integer attemptCount(UUID operationId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from execution_attempts where operation_id = ?",
				Integer.class,
				operationId
		);
	}

	private Integer artifactCount(UUID operationId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				operationId
		);
	}

	private Integer outboxCount(UUID operationId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from dispatch_outbox where operation_id = ?",
				Integer.class,
				operationId
		);
	}

	private Integer decisionCount(UUID operationId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from scheduling_decisions where operation_id = ?",
				Integer.class,
				operationId
		);
	}

	private record Started(UUID jobId, UUID operationId, UUID attemptId, UUID assignmentId) {
	}
}
