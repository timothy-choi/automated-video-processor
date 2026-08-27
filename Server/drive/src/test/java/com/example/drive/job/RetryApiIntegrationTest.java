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
class RetryApiIntegrationTest extends AuthenticatedApiTest {

	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private InternalOperationService internalOperationService;

	@Autowired
	private JobRetryService jobRetryService;

	@Autowired
	private JobCancellationService jobCancellationService;

	@Autowired
	private AssignmentRecoveryService assignmentRecoveryService;

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
	void failedOperationRetryReturnsQueuedAndReactivatesJob() throws Exception {
		Started started = assignAndStart("METADATA");
		fail(started, "mpeg4 is not h264");
		assertThat(jobStatus(started.jobId())).isEqualTo("FAILED");

		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/retry")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(started.jobId().toString()))
				.andExpect(jsonPath("$.jobStatus").value("QUEUED"))
				.andExpect(jsonPath("$.operationId").value(started.operationId().toString()))
				.andExpect(jsonPath("$.operationStatus").value("QUEUED"))
				.andExpect(jsonPath("$.attemptCount").value(1));

		assertThat(operationStatus(started.operationId())).isEqualTo("QUEUED");
		assertThat(failureReason(started.operationId())).isNull();
		assertThat(currentAttemptId(started.operationId())).isNull();
		assertThat(currentAssignmentId(started.operationId())).isNull();
		assertThat(attemptStatus(started.attemptId())).isEqualTo("FAILED");
		assertThat(attemptFailureReason(started.attemptId())).isEqualTo("mpeg4 is not h264");
		assertThat(decisionCount(started.operationId())).isEqualTo(1);
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations[0].operationId").value(started.operationId().toString()));
	}

	@Test
	void completedSiblingsKeepJobRunningAfterRetry() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[
				  {"type":"METADATA"},{"type":"THUMBNAIL"}
				]}
				""");
		UUID metadataId = operationId(jobId, "METADATA");
		UUID thumbnailId = operationId(jobId, "THUMBNAIL");
		UUID thumbAttempt = assignAndStart(jobId, thumbnailId).attemptId();
		completeThumbnail(jobId, thumbnailId, thumbAttempt);
		UUID metaAttempt = assignAndStart(jobId, metadataId).attemptId();
		internalOperationService.fail(metadataId, new FailOperationRequest(metaAttempt, 9L, "probe failed"));
		assertThat(jobStatus(jobId)).isEqualTo("FAILED");

		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + metadataId + "/retry")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobStatus").value("RUNNING"))
				.andExpect(jsonPath("$.operationStatus").value("QUEUED"));
		assertThat(operationStatus(thumbnailId)).isEqualTo("COMPLETED");
		assertThat(artifactCount(thumbnailId)).isEqualTo(1);
	}

	@Test
	void completedOperationCannotBeRetried() throws Exception {
		Started started = assignAndStart("METADATA");
		completeMetadata(started.operationId(), started.attemptId());
		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/retry")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"));
		assertThat(operationStatus(started.operationId())).isEqualTo("COMPLETED");
		assertThat(attemptCount(started.operationId())).isEqualTo(1);
	}

	@Test
	void cancelledOperationCannotBeRetried() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID operationId = operationId(jobId, "METADATA");
		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + operationId + "/cancel")))
				.andExpect(status().isOk());
		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + operationId + "/retry")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("intentionally cancelled")));
		assertThat(operationStatus(operationId)).isEqualTo("CANCELLED");
	}

	@Test
	void runningAndQueuedOperationsCannotBeRetried() throws Exception {
		Started started = assignAndStart("METADATA");
		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/retry")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"));
		UUID queuedJob = createJob("""
				{"inputUri":"s3://media-input/other.mp4","operations":[{"type":"THUMBNAIL"}]}
				""");
		UUID queuedOp = operationId(queuedJob, "THUMBNAIL");
		mockMvc.perform(authed(post("/jobs/" + queuedJob + "/operations/" + queuedOp + "/retry")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"));
	}

	@Test
	void unknownJobAndOperationReturn404() throws Exception {
		UUID missing = UUID.fromString("99999999-9999-9999-9999-999999999999");
		mockMvc.perform(authed(post("/jobs/" + missing + "/operations/" + missing + "/retry")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
		UUID jobA = createJob("""
				{"inputUri":"s3://media-input/a.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID jobB = createJob("""
				{"inputUri":"s3://media-input/b.mp4","operations":[{"type":"THUMBNAIL"}]}
				""");
		UUID opB = operationId(jobB, "THUMBNAIL");
		mockMvc.perform(authed(post("/jobs/" + jobA + "/operations/" + opB + "/retry")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
		mockMvc.perform(authed(post("/jobs/" + missing + "/retry")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
	}

	@Test
	void retryThenStartCreatesAttemptTwoAndKeepsAttemptOneFailed() throws Exception {
		Started started = assignAndStart("METADATA");
		fail(started, "bad input");
		retry(started.jobId(), started.operationId());
		assertThat(queuedAt(started.operationId())).isAfter(createdAt(started.operationId()));

		Started second = assignAndStart(started.jobId(), started.operationId());
		assertThat(second.attemptId()).isNotEqualTo(started.attemptId());
		assertThat(attemptNumber(second.attemptId())).isEqualTo(2);
		assertThat(attemptStatus(started.attemptId())).isEqualTo("FAILED");
		assertThat(decisionCount(started.operationId())).isEqualTo(2);

		completeMetadata(started.operationId(), second.attemptId());
		mockMvc.perform(authed(get("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/attempts")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.attempts.length()").value(2))
				.andExpect(jsonPath("$.attempts[0].status").value("FAILED"))
				.andExpect(jsonPath("$.attempts[0].attemptNumber").value(1))
				.andExpect(jsonPath("$.attempts[1].status").value("COMPLETED"))
				.andExpect(jsonPath("$.attempts[1].attemptNumber").value(2));
		assertThat(jobStatus(started.jobId())).isEqualTo("COMPLETED");
	}

	@Test
	void staleAttemptOneCannotMutateRetriedOperation() throws Exception {
		Started first = assignAndStart("METADATA");
		fail(first, "first failed");
		retry(first.jobId(), first.operationId());
		Started second = assignAndStart(first.jobId(), first.operationId());

		mockMvc.perform(post("/internal/operations/" + first.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(metadataJson(first.attemptId())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		mockMvc.perform(post("/internal/operations/" + first.operationId() + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"attemptId\":\"" + first.attemptId() + "\",\"reason\":\"late fail\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		mockMvc.perform(post(
						"/internal/operations/" + first.operationId() + "/attempts/" + first.attemptId() + "/cancelled"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"workerId\":\"worker-a\",\"actualRuntimeMs\":3}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		assertThat(operationStatus(first.operationId())).isEqualTo("RUNNING");
		assertThat(attemptStatus(second.attemptId())).isEqualTo("RUNNING");
		assertThat(artifactCount(first.operationId())).isZero();
	}

	@Test
	void retryDoesNotDeleteSiblingArtifacts() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[
				  {"type":"THUMBNAIL"},{"type":"METADATA"}
				]}
				""");
		UUID thumbnailId = operationId(jobId, "THUMBNAIL");
		UUID metadataId = operationId(jobId, "METADATA");
		UUID thumbAttempt = assignAndStart(jobId, thumbnailId).attemptId();
		completeThumbnail(jobId, thumbnailId, thumbAttempt);
		UUID metaAttempt = assignAndStart(jobId, metadataId).attemptId();
		internalOperationService.fail(metadataId, new FailOperationRequest(metaAttempt, 4L, "no video"));
		retry(jobId, metadataId);
		assertThat(artifactCount(thumbnailId)).isEqualTo(1);
		assertThat(artifactCount(metadataId)).isZero();
	}

	@Test
	void concurrentRetryOnlyOneTransitionsFromFailed() throws Exception {
		Started started = assignAndStart("METADATA");
		fail(started, "boom");
		AtomicReference<Throwable> firstErr = new AtomicReference<>();
		AtomicReference<Throwable> secondErr = new AtomicReference<>();
		runConcurrent(
				() -> {
					try {
						jobRetryService.retryOperation(started.jobId(), started.operationId(), account.accountId());
					}
					catch (RuntimeException ex) {
						firstErr.set(ex);
					}
				},
				() -> {
					try {
						jobRetryService.retryOperation(started.jobId(), started.operationId(), account.accountId());
					}
					catch (RuntimeException ex) {
						secondErr.set(ex);
					}
				}
		);
		assertThat(operationStatus(started.operationId())).isEqualTo("QUEUED");
		assertThat(jobStatus(started.jobId())).isEqualTo("QUEUED");
		long conflicts = java.util.stream.Stream.of(firstErr.get(), secondErr.get())
				.filter(err -> err instanceof IllegalOperationStateException)
				.count();
		assertThat(conflicts).isEqualTo(1);
		assertThat(java.util.stream.Stream.of(firstErr.get(), secondErr.get()).filter(java.util.Objects::isNull).count())
				.isEqualTo(1);
	}

	@Test
	void retryVersusCancelLeavesConsistentState() throws Exception {
		Started started = assignAndStart("METADATA");
		fail(started, "boom");
		AtomicReference<Throwable> retryErr = new AtomicReference<>();
		AtomicReference<Throwable> cancelErr = new AtomicReference<>();
		runConcurrent(
				() -> {
					try {
						jobRetryService.retryOperation(started.jobId(), started.operationId(), account.accountId());
					}
					catch (RuntimeException ex) {
						retryErr.set(ex);
					}
				},
				() -> {
					try {
						jobCancellationService.cancelJob(started.jobId(), account.accountId());
					}
					catch (RuntimeException ex) {
						cancelErr.set(ex);
					}
				}
		);
		String status = operationStatus(started.operationId());
		assertThat(status).isIn("QUEUED", "CANCELLED");
		if ("QUEUED".equals(status)) {
			assertThat(jobStatus(started.jobId())).isEqualTo("QUEUED");
			assertThat(cancelErr.get()).isInstanceOf(JobAlreadyTerminalException.class);
			assertThat(retryErr.get()).isNull();
		}
		else {
			assertThat(jobStatus(started.jobId())).isEqualTo("CANCELLED");
			assertThat(retryErr.get()).isNull();
			assertThat(cancelErr.get()).isNull();
		}
	}

	@Test
	void retriedWorkReentersFifoBehindNewerQueuedOperations() throws Exception {
		Started older = assignAndStart("METADATA");
		fail(older, "first");
		UUID newerJob = createJob("""
				{"inputUri":"s3://media-input/newer.mp4","operations":[{"type":"THUMBNAIL"}]}
				""");
		UUID newerOp = operationId(newerJob, "THUMBNAIL");
		retry(older.jobId(), older.operationId());
		assertThat(queuedAt(older.operationId())).isAfter(queuedAt(newerOp));
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations[0].operationId").value(newerOp.toString()))
				.andExpect(jsonPath("$.operations[1].operationId").value(older.operationId().toString()));
	}

	@Test
	void leaseRecoveryRefreshesQueuedAt() throws Exception {
		Started started = assignAndStart("METADATA");
		Instant originalQueue = queuedAt(started.operationId());
		expireLease(started.attemptId());
		markUnavailable("worker-a");
		assertThat(internalOperationService.reclaimExpiredAttempts()).isEqualTo(1);
		assertThat(operationStatus(started.operationId())).isEqualTo("QUEUED");
		assertThat(queuedAt(started.operationId())).isAfter(originalQueue);
		assertThat(createdAt(started.operationId())).isEqualTo(originalQueue);
	}

	@Test
	void assignmentTimeoutRecoveryRefreshesQueuedAt() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		UUID operationId = operationId(jobId, "METADATA");
		Instant originalQueue = queuedAt(operationId);
		schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		jdbcTemplate.update(
				"update operations set assigned_at = ? where id = ?",
				Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
				operationId
		);
		markUnavailable("worker-a");
		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isEqualTo(1);
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		assertThat(queuedAt(operationId)).isAfter(originalQueue);
	}

	@Test
	void jobLevelRetryQueuesOnlyFailedOperations() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[
				  {"type":"METADATA"},{"type":"THUMBNAIL"}
				]}
				""");
		UUID metadataId = operationId(jobId, "METADATA");
		UUID thumbnailId = operationId(jobId, "THUMBNAIL");
		UUID thumbAttempt = assignAndStart(jobId, thumbnailId).attemptId();
		completeThumbnail(jobId, thumbnailId, thumbAttempt);
		UUID metaAttempt = assignAndStart(jobId, metadataId).attemptId();
		internalOperationService.fail(metadataId, new FailOperationRequest(metaAttempt, 8L, "bad"));
		mockMvc.perform(authed(post("/jobs/" + jobId + "/retry")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(jobId.toString()))
				.andExpect(jsonPath("$.jobStatus").value("RUNNING"))
				.andExpect(jsonPath("$.retriedOperations.length()").value(1))
				.andExpect(jsonPath("$.retriedOperations[0].operationId").value(metadataId.toString()))
				.andExpect(jsonPath("$.retriedOperations[0].operationStatus").value("QUEUED"));
		assertThat(operationStatus(thumbnailId)).isEqualTo("COMPLETED");
		assertThat(artifactCount(thumbnailId)).isEqualTo(1);
	}

	@Test
	void jobLevelRetryWithNothingFailedReturns409() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		mockMvc.perform(authed(post("/jobs/" + jobId + "/retry")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NOTHING_TO_RETRY"));
		Started started = assignAndStart("THUMBNAIL");
		completeThumbnail(started.jobId(), started.operationId(), started.attemptId());
		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/retry")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NOTHING_TO_RETRY"));
	}

	@Test
	void duplicateRetryAfterQueueIsConflict() throws Exception {
		Started started = assignAndStart("METADATA");
		fail(started, "once");
		retry(started.jobId(), started.operationId());
		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/retry")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"));
	}

	@Test
	void userRetryAfterMaxInfrastructureAttemptsStartsNewAttempt() throws Exception {
		Started started = assignAndStart("METADATA");
		UUID current = started.attemptId();
		for (int attempt = 1; attempt <= 3; attempt++) {
			expireLease(current);
			markUnavailable("worker-a");
			internalOperationService.reclaimExpiredAttempts();
			if (attempt < 3) {
				assertThat(operationStatus(started.operationId())).isEqualTo("QUEUED");
				jdbcTemplate.update("update workers set status = 'AVAILABLE' where id = 'worker-a'");
				current = assignAndStart(started.jobId(), started.operationId()).attemptId();
			}
		}
		assertThat(operationStatus(started.operationId())).isEqualTo("FAILED");
		retry(started.jobId(), started.operationId());
		jdbcTemplate.update("update workers set status = 'AVAILABLE' where id = 'worker-a'");
		Started fourth = assignAndStart(started.jobId(), started.operationId());
		assertThat(attemptNumber(fourth.attemptId())).isEqualTo(4);
		assertThat(operationStatus(started.operationId())).isEqualTo("RUNNING");
	}

	private Started assignAndStart(String type) throws Exception {
		UUID jobId = createJob("{\"inputUri\":\"s3://media-input/video.mp4\",\"operations\":[{\"type\":\"" + type + "\"}]}");
		return assignAndStart(jobId, operationId(jobId, type));
	}

	private Started assignAndStart(UUID jobId, UUID operationId) throws Exception {
		var assigned = schedulerService.assign(
				new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC")
		);
		MvcResult result = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-a", assigned.decisionId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andReturn();
		UUID attemptId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.attemptId"));
		return new Started(jobId, operationId, attemptId);
	}

	private void fail(Started started, String reason) {
		internalOperationService.fail(
				started.operationId(),
				new FailOperationRequest(started.attemptId(), 11L, reason)
		);
	}

	private void retry(UUID jobId, UUID operationId) throws Exception {
		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + operationId + "/retry")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationStatus").value("QUEUED"));
	}

	private void completeMetadata(UUID operationId, UUID attemptId) {
		internalOperationService.complete(operationId, new CompleteOperationRequest(
				attemptId,
				12L,
				new MetadataResultDto(2.0, "mp4", 100L, "h264", null, 320, 240, 30.0),
				null
		));
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

	private static String metadataJson(UUID attemptId) {
		return """
				{
				  "attemptId": "%s",
				  "actualRuntimeMs": 12,
				  "metadata": {
				    "durationSeconds": 2.0,
				    "formatName": "mp4",
				    "sizeBytes": 100,
				    "videoCodec": "h264",
				    "width": 320,
				    "height": 240,
				    "frameRate": 30.0
				  }
				}
				""".formatted(attemptId);
	}

	private UUID createJob(String body) {
		try {
			MvcResult result = mockMvc.perform(authed(post("/jobs"))
							.contentType(MediaType.APPLICATION_JSON)
							.content(body))
					.andExpect(status().isAccepted())
					.andReturn();
			return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
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

	private void markUnavailable(String workerId) {
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = ?", workerId);
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
		return jdbcTemplate.queryForObject("select status from execution_attempts where id = ?", String.class, attemptId);
	}

	private int attemptNumber(UUID attemptId) {
		Integer value = jdbcTemplate.queryForObject(
				"select attempt_number from execution_attempts where id = ?",
				Integer.class,
				attemptId
		);
		return value == null ? 0 : value;
	}

	private int attemptCount(UUID operationId) {
		Integer value = jdbcTemplate.queryForObject(
				"select count(*) from execution_attempts where operation_id = ?",
				Integer.class,
				operationId
		);
		return value == null ? 0 : value;
	}

	private int decisionCount(UUID operationId) {
		Integer value = jdbcTemplate.queryForObject(
				"select count(*) from scheduling_decisions where operation_id = ?",
				Integer.class,
				operationId
		);
		return value == null ? 0 : value;
	}

	private int artifactCount(UUID operationId) {
		Integer value = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				operationId
		);
		return value == null ? 0 : value;
	}

	private String failureReason(UUID operationId) {
		return jdbcTemplate.queryForObject(
				"select failure_reason from operations where id = ?",
				String.class,
				operationId
		);
	}

	private String attemptFailureReason(UUID attemptId) {
		return jdbcTemplate.queryForObject(
				"select failure_reason from execution_attempts where id = ?",
				String.class,
				attemptId
		);
	}

	private Instant queuedAt(UUID operationId) {
		Timestamp value = jdbcTemplate.queryForObject(
				"select queued_at from operations where id = ?",
				Timestamp.class,
				operationId
		);
		return value.toInstant();
	}

	private Instant createdAt(UUID operationId) {
		Timestamp value = jdbcTemplate.queryForObject(
				"select created_at from operations where id = ?",
				Timestamp.class,
				operationId
		);
		return value.toInstant();
	}

	private record Started(UUID jobId, UUID operationId, UUID attemptId) {
	}
}
