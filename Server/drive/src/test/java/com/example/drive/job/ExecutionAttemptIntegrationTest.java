package com.example.drive.job;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.dispatch.DispatchEnqueueService;
import com.example.drive.support.DispatchServiceTest;
import com.example.drive.support.WorkerTestSupport;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DispatchServiceTest
class ExecutionAttemptIntegrationTest {

	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DispatchEnqueueService enqueueService;

	@Autowired
	private InternalOperationService internalOperationService;

	@Autowired
	private Clock clock;

	@BeforeEach
	void clearTables() throws Exception {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
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
	void startCreatesRunningAttemptWithLease() throws Exception {
		Started started = assignAndStart("worker-a");

		mockMvc.perform(get("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/attempts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.attempts.length()").value(1))
				.andExpect(jsonPath("$.attempts[0].attemptNumber").value(1))
				.andExpect(jsonPath("$.attempts[0].workerId").value("worker-a"))
				.andExpect(jsonPath("$.attempts[0].status").value("RUNNING"))
				.andExpect(jsonPath("$.attempts[0].startedAt").isString())
				.andExpect(jsonPath("$.attempts[0].id").value(started.attemptId().toString()));

		Instant leaseExpiresAt = jdbcTemplate.queryForObject(
				"select lease_expires_at from execution_attempts where id = ?",
				Timestamp.class,
				started.attemptId()
		).toInstant();
		assertThat(leaseExpiresAt).isAfter(clock.instant());
		assertThat(operationStatus(started.operationId())).isEqualTo("RUNNING");
	}

	@Test
	void unavailableWorkerCannotStart() throws Exception {
		UUID operationId = createAssignedMetadata();
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-a'");

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WORKER_UNAVAILABLE"));
		assertThat(attemptCount(operationId)).isZero();
	}

	@Test
	void capabilityMismatchCannotStart() throws Exception {
		WorkerTestSupport.register(mockMvc, "metadata-only", "METADATA");
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = operationId(jobId);

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("metadata-only")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WORKER_CAPABILITY_MISMATCH"));
		assertThat(attemptCount(operationId)).isZero();
	}

	@Test
	void duplicateStartDoesNotCreateSecondAttempt() throws Exception {
		Started started = assignAndStart("worker-a");
		mockMvc.perform(post("/internal/operations/" + started.operationId() + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-b")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ALREADY_RUNNING"));
		assertThat(attemptCount(started.operationId())).isEqualTo(1);
	}

	@Test
	void leaseRenewExtendsExpiration() throws Exception {
		Started started = assignAndStart("worker-a");
		Instant original = leaseExpiresAt(started.attemptId());
		jdbcTemplate.update(
				"update execution_attempts set lease_expires_at = ? where id = ?",
				Timestamp.from(clock.instant().plusSeconds(5)),
				started.attemptId()
		);

		MvcResult renewed = mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/renew"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.attemptId").value(started.attemptId().toString()))
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andReturn();
		Instant updated = Instant.parse(JsonPath.read(renewed.getResponse().getContentAsString(), "$.leaseExpiresAt"));
		assertThat(updated).isAfter(original.minusSeconds(30));
		assertThat(leaseExpiresAt(started.attemptId())).isEqualTo(updated);
		assertThat(attemptCount(started.operationId())).isEqualTo(1);
	}

	@Test
	void wrongWorkerCannotRenew() throws Exception {
		Started started = assignAndStart("worker-a");
		mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/renew"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-b")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
	}

	@Test
	void terminalAttemptCannotRenew() throws Exception {
		Started started = assignAndStart("worker-a");
		completeMetadata(started.operationId(), started.attemptId());
		mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/renew"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
	}

	@Test
	void expiredLeaseWithUnavailableWorkerIsInterruptedAndRequeued() throws Exception {
		Started started = assignAndStart("worker-a");
		expireLease(started.attemptId());
		markUnavailable("worker-a");

		assertThat(internalOperationService.reclaimExpiredAttempts()).isEqualTo(1);
		assertThat(attemptStatus(started.attemptId())).isEqualTo("INTERRUPTED");
		assertThat(operationStatus(started.operationId())).isEqualTo("QUEUED");
		assertThat(currentAttemptId(started.operationId())).isNull();
	}

	@Test
	void freshLeaseIsNotReclaimed() throws Exception {
		Started started = assignAndStart("worker-a");
		markUnavailable("worker-a");
		assertThat(internalOperationService.reclaimExpiredAttempts()).isZero();
		assertThat(attemptStatus(started.attemptId())).isEqualTo("RUNNING");
		assertThat(operationStatus(started.operationId())).isEqualTo("RUNNING");
	}

	@Test
	void expiredLeaseForAvailableWorkerIsNotReclaimed() throws Exception {
		Started started = assignAndStart("worker-a");
		expireLease(started.attemptId());
		assertThat(internalOperationService.reclaimExpiredAttempts()).isZero();
		assertThat(attemptStatus(started.attemptId())).isEqualTo("RUNNING");
		assertThat(operationStatus(started.operationId())).isEqualTo("RUNNING");
	}

	@Test
	void heartbeatUnavailabilityAloneDoesNotReclaim() throws Exception {
		Started started = assignAndStart("worker-a");
		markUnavailable("worker-a");
		assertThat(internalOperationService.reclaimExpiredAttempts()).isZero();
		assertThat(operationStatus(started.operationId())).isEqualTo("RUNNING");
	}

	@Test
	void reclaimedOperationIsRedispatchedAndStartsAttemptTwo() throws Exception {
		Started first = assignAndStart("worker-a");
		expireLease(first.attemptId());
		markUnavailable("worker-a");
		assertThat(internalOperationService.reclaimExpiredAttempts()).isEqualTo(1);
		assertThat(operationStatus(first.operationId())).isEqualTo("QUEUED");

		assertThat(enqueueService.enqueueDispatchableOperations()).isEqualTo(1);
		assertThat(operationStatus(first.operationId())).isEqualTo("ASSIGNED");

		MvcResult started = mockMvc.perform(post("/internal/operations/" + first.operationId() + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-b")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andExpect(jsonPath("$.workerId").value("worker-b"))
				.andReturn();
		UUID attempt2 = UUID.fromString(JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId"));
		assertThat(attempt2).isNotEqualTo(first.attemptId());
		assertThat(attemptNumber(attempt2)).isEqualTo(2);
		assertThat(attemptStatus(first.attemptId())).isEqualTo("INTERRUPTED");
		assertThat(attemptStatus(attempt2)).isEqualTo("RUNNING");
	}

	@Test
	void staleAttemptOneCompletionIsRejectedAfterAttemptTwoStarts() throws Exception {
		Started first = assignAndStart("worker-a");
		expireLease(first.attemptId());
		markUnavailable("worker-a");
		internalOperationService.reclaimExpiredAttempts();
		enqueueService.enqueueDispatchableOperations();

		MvcResult started = mockMvc.perform(post("/internal/operations/" + first.operationId() + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-b")))
				.andExpect(status().isOk())
				.andReturn();
		UUID attempt2 = UUID.fromString(JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId"));

		mockMvc.perform(post("/internal/operations/" + first.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "attemptId": "%s",
								  "actualRuntimeMs": 50,
								  "metadata": {"formatName": "mp4", "width": 999}
								}
								""".formatted(first.attemptId())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));

		assertThat(attemptStatus(first.attemptId())).isEqualTo("INTERRUPTED");
		assertThat(attemptStatus(attempt2)).isEqualTo("RUNNING");
		assertThat(operationStatus(first.operationId())).isEqualTo("RUNNING");
		assertThat(jobStatus(first.jobId())).isEqualTo("RUNNING");

		completeMetadata(first.operationId(), attempt2);
		assertThat(operationStatus(first.operationId())).isEqualTo("COMPLETED");
		assertThat(jobStatus(first.jobId())).isEqualTo("COMPLETED");
		assertThat(attemptStatus(attempt2)).isEqualTo("COMPLETED");

		mockMvc.perform(get("/jobs/" + first.jobId() + "/operations/" + first.operationId() + "/attempts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.attempts.length()").value(2))
				.andExpect(jsonPath("$.attempts[0].status").value("INTERRUPTED"))
				.andExpect(jsonPath("$.attempts[1].status").value("COMPLETED"));
	}

	@Test
	void staleThumbnailCompletionDoesNotWriteArtifact() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = operationId(jobId);
		UUID attempt1 = start(operationId, "worker-a");
		expireLease(attempt1);
		markUnavailable("worker-a");
		internalOperationService.reclaimExpiredAttempts();
		enqueueService.enqueueDispatchableOperations();
		UUID attempt2 = start(operationId, "worker-b");

		String staleUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/stale.jpg";
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(thumbnailJson(attempt1, staleUri)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		Integer artifacts = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				operationId
		);
		assertThat(artifacts).isZero();

		String liveUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg";
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(thumbnailJson(attempt2, liveUri)))
				.andExpect(status().isOk());
		Integer stored = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ? and object_uri = ?",
				Integer.class,
				operationId,
				liveUri
		);
		assertThat(stored).isEqualTo(1);
		assertThat(jobStatus(jobId)).isEqualTo("COMPLETED");
	}

	@Test
	void staleAudioCompletionDoesNotWriteArtifact() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "AUDIO_EXTRACTION"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = operationId(jobId);
		UUID attempt1 = start(operationId, "worker-a");
		expireLease(attempt1);
		markUnavailable("worker-a");
		internalOperationService.reclaimExpiredAttempts();
		enqueueService.enqueueDispatchableOperations();
		UUID attempt2 = start(operationId, "worker-b");

		String staleUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/stale.m4a";
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(audioJson(attempt1, staleUri)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		Integer artifacts = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				operationId
		);
		assertThat(artifacts).isZero();

		String liveUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/audio.m4a";
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(audioJson(attempt2, liveUri)))
				.andExpect(status().isOk());
		Integer stored = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ? and object_uri = ? and artifact_type = 'AUDIO'",
				Integer.class,
				operationId,
				liveUri
		);
		assertThat(stored).isEqualTo(1);
		assertThat(jobStatus(jobId)).isEqualTo("COMPLETED");
	}

	@Test
	void staleTranscodeCompletionDoesNotWriteArtifact() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "TRANSCODE_1080P"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = operationId(jobId);
		UUID attempt1 = start(operationId, "worker-a");
		expireLease(attempt1);
		markUnavailable("worker-a");
		internalOperationService.reclaimExpiredAttempts();
		enqueueService.enqueueDispatchableOperations();
		UUID attempt2 = start(operationId, "worker-b");

		String staleUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/stale.mp4";
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(transcodeJson(attempt1, staleUri)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		Integer artifacts = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				operationId
		);
		assertThat(artifacts).isZero();

		String liveUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/video-1080p.mp4";
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(transcodeJson(attempt2, liveUri)))
				.andExpect(status().isOk());
		Integer stored = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ? and object_uri = ? and artifact_type = 'TRANSCODE_1080P'",
				Integer.class,
				operationId,
				liveUri
		);
		assertThat(stored).isEqualTo(1);
		assertThat(jobStatus(jobId)).isEqualTo("COMPLETED");
	}

	@Test
	void staleAv1CompletionDoesNotWriteArtifact() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "H264_TO_AV1"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = operationId(jobId);
		UUID attempt1 = start(operationId, "worker-a");
		expireLease(attempt1);
		markUnavailable("worker-a");
		internalOperationService.reclaimExpiredAttempts();
		enqueueService.enqueueDispatchableOperations();
		UUID attempt2 = start(operationId, "worker-b");

		String staleUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/stale-av1.mp4";
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(transcodeJson(attempt1, staleUri)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		Integer artifacts = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				operationId
		);
		assertThat(artifacts).isZero();

		String liveUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/video-av1.mp4";
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(transcodeJson(attempt2, liveUri)))
				.andExpect(status().isOk());
		Integer storedAv1 = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ? and object_uri = ? and artifact_type = 'H264_TO_AV1'",
				Integer.class,
				operationId,
				liveUri
		);
		assertThat(storedAv1).isEqualTo(1);
		assertThat(jobStatus(jobId)).isEqualTo("COMPLETED");
	}

	@Test
	void staleFailureIsRejected() throws Exception {
		Started first = assignAndStart("worker-a");
		expireLease(first.attemptId());
		markUnavailable("worker-a");
		internalOperationService.reclaimExpiredAttempts();
		enqueueService.enqueueDispatchableOperations();
		UUID attempt2 = start(first.operationId(), "worker-b");

		mockMvc.perform(post("/internal/operations/" + first.operationId() + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"attemptId": "%s", "reason": "late failure from worker-a"}
								""".formatted(first.attemptId())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
		assertThat(operationStatus(first.operationId())).isEqualTo("RUNNING");
		assertThat(attemptStatus(attempt2)).isEqualTo("RUNNING");
	}

	@Test
	void maxAttemptsFailsOperationInsteadOfRequeue() throws Exception {
		Started started = assignAndStart("worker-a");
		for (int attempt = 1; attempt <= 3; attempt++) {
			UUID current = UUID.fromString(jdbcTemplate.queryForObject(
					"select id from execution_attempts where operation_id = ? and status = 'RUNNING'",
					String.class,
					started.operationId()
			));
			expireLease(current);
			markUnavailable("worker-a");
			internalOperationService.reclaimExpiredAttempts();
			if (attempt < 3) {
				assertThat(operationStatus(started.operationId())).isEqualTo("QUEUED");
				enqueueService.enqueueDispatchableOperations();
				jdbcTemplate.update("update workers set status = 'AVAILABLE' where id = 'worker-a'");
				start(started.operationId(), "worker-a");
			}
		}
		assertThat(operationStatus(started.operationId())).isEqualTo("FAILED");
		assertThat(jobStatus(started.jobId())).isEqualTo("FAILED");
		assertThat(attemptCount(started.operationId())).isEqualTo(3);
		String reason = jdbcTemplate.queryForObject(
				"select failure_reason from operations where id = ?",
				String.class,
				started.operationId()
		);
		assertThat(reason).isEqualTo("maximum execution attempts exceeded");
	}

	private Started assignAndStart(String workerId) throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/sample.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = operationId(jobId);
		UUID attemptId = start(operationId, workerId);
		return new Started(jobId, operationId, attemptId);
	}

	private UUID start(UUID operationId, String workerId) throws Exception {
		MvcResult result = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson(workerId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.attemptId"));
	}

	private UUID createAssignedMetadata() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/sample.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		return operationId(jobId);
	}

	private UUID createJob(String json) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}

	private void completeMetadata(UUID operationId, UUID attemptId) throws Exception {
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "attemptId": "%s",
								  "actualRuntimeMs": 8,
								  "metadata": {"formatName": "mp4"}
								}
								""".formatted(attemptId)))
				.andExpect(status().isOk());
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

	private Instant leaseExpiresAt(UUID attemptId) {
		return jdbcTemplate.queryForObject(
				"select lease_expires_at from execution_attempts where id = ?",
				Timestamp.class,
				attemptId
		).toInstant();
	}

	private UUID operationId(UUID jobId) {
		return UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ?",
				String.class,
				jobId
		));
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
		return jdbcTemplate.queryForObject(
				"select attempt_number from execution_attempts where id = ?",
				Integer.class,
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

	private UUID currentAttemptId(UUID operationId) {
		return jdbcTemplate.query(
				"select current_attempt_id from operations where id = ?",
				rs -> rs.next() ? rs.getObject("current_attempt_id", UUID.class) : null,
				operationId
		);
	}

	private static String thumbnailJson(UUID attemptId, String objectUri) {
		return """
				{
				  "attemptId": "%s",
				  "actualRuntimeMs": 20,
				  "artifact": {
				    "objectUri": "%s",
				    "contentType": "image/jpeg",
				    "sizeBytes": 50,
				    "checksum": "%s"
				  }
				}
				""".formatted(attemptId, objectUri, SHA256);
	}

	private static String audioJson(UUID attemptId, String objectUri) {
		return """
				{
				  "attemptId": "%s",
				  "actualRuntimeMs": 20,
				  "artifact": {
				    "objectUri": "%s",
				    "contentType": "audio/mp4",
				    "sizeBytes": 50,
				    "checksum": "%s"
				  }
				}
				""".formatted(attemptId, objectUri, SHA256);
	}

	private static String transcodeJson(UUID attemptId, String objectUri) {
		return """
				{
				  "attemptId": "%s",
				  "actualRuntimeMs": 20,
				  "artifact": {
				    "objectUri": "%s",
				    "contentType": "video/mp4",
				    "sizeBytes": 50,
				    "checksum": "%s"
				  }
				}
				""".formatted(attemptId, objectUri, SHA256);
	}

	private record Started(UUID jobId, UUID operationId, UUID attemptId) {
	}
}
