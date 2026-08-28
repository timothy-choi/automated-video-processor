package com.example.drive.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.job.dto.ArtifactCompletionDto;
import com.example.drive.job.dto.CompleteOperationRequest;
import com.example.drive.job.dto.FailOperationRequest;
import com.example.drive.job.dto.MetadataResultDto;
import com.example.drive.scheduler.SchedulerService;
import com.example.drive.scheduler.dto.AssignOperationRequest;
import com.example.drive.support.AuthTestSupport;
import com.example.drive.support.AuthenticatedApiTest;
import com.example.drive.support.DispatchServiceTest;
import com.example.drive.support.WorkerTestSupport;
import com.jayway.jsonpath.JsonPath;

@DispatchServiceTest
class JobTimelineApiIntegrationTest extends AuthenticatedApiTest {

	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private InternalOperationService internalOperationService;

	@Autowired
	private SchedulerService schedulerService;

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
	void ownerCanReadCompletedJobTimeline() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"},{"type":"THUMBNAIL"}]}
				""");
		UUID metadataId = operationId(jobId, "METADATA");
		UUID thumbnailId = operationId(jobId, "THUMBNAIL");
		Started metadata = assignAndStart(jobId, metadataId, "worker-a", "FIFO", "LEAST_LOADED");
		completeMetadata(metadata.operationId(), metadata.attemptId());
		Started thumbnail = assignAndStart(jobId, thumbnailId, "worker-a", "FIFO", "LEAST_LOADED");
		completeThumbnail(jobId, thumbnail.operationId(), thumbnail.attemptId());

		Instant updatedBefore = updatedAt(jobId);
		MvcResult result = mockMvc.perform(authed(get("/jobs/" + jobId + "/timeline")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(jobId.toString()))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.operationCount").value(2))
				.andExpect(jsonPath("$.completedOperationCount").value(2))
				.andExpect(jsonPath("$.failedOperationCount").value(0))
				.andExpect(jsonPath("$.artifactCount").value(1))
				.andExpect(jsonPath("$.events[0].type").value("JOB_CREATED"))
				.andExpect(jsonPath("$.events[0].sortKey").doesNotExist())
				.andExpect(jsonPath("$.operations.length()").value(2))
				.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(updatedAt(jobId)).isEqualTo(updatedBefore);
		List<String> types = JsonPath.read(body, "$.events[*].type");
		assertThat(types).contains("JOB_CREATED", "OPERATION_QUEUED", "OPERATION_ASSIGNED", "OPERATION_STARTED",
				"ARTIFACT_CREATED", "OPERATION_COMPLETED", "JOB_COMPLETED");
		assertThat(types).doesNotContain("OPERATION_CANCEL_REQUESTED", "JOB_RUNNING");
		assertTimestampsNonDecreasing(body);
		assertThat(((Number) JsonPath.read(body, "$.durationMs")).longValue()).isGreaterThanOrEqualTo(0);
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.type=='OPERATION_ASSIGNED')].workerId"))
				.containsOnly("worker-a");
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.type=='OPERATION_ASSIGNED')].workerPolicy"))
				.containsOnly("LEAST_LOADED");
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.type=='OPERATION_ASSIGNED')].operationPolicy"))
				.containsOnly("FIFO");
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.type=='ARTIFACT_CREATED')].artifactType"))
				.containsExactly("THUMBNAIL");
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.type=='ARTIFACT_CREATED')].checksum"))
				.containsExactly(SHA256);
		assertNoSecrets(body);
		assertThat(body).doesNotContain("downloadUrl", "objectUri", "X-Amz-", "routingKey", "leaseExpiresAt");

		List<Number> queueWaits = JsonPath.read(body, "$.operations[*].queueWaitMs");
		List<Number> assignmentWaits = JsonPath.read(body, "$.operations[*].assignmentWaitMs");
		List<Number> runtimes = JsonPath.read(body, "$.operations[*].executionRuntimeMs");
		assertThat(queueWaits).allMatch(value -> value.longValue() >= 0);
		assertThat(assignmentWaits).allMatch(value -> value.longValue() >= 0);
		assertThat(runtimes).containsExactlyInAnyOrder(12, 20);
	}

	@Test
	void failedAttemptExposesSafeReason() throws Exception {
		Started started = assignAndStart("METADATA");
		internalOperationService.fail(started.operationId(), new FailOperationRequest(
				started.attemptId(),
				11L,
				"""
						java.lang.IllegalStateException: FFmpeg exited non-zero
							at com.example.drive.job.InternalOperationService.fail(InternalOperationService.java:12)
						"""
		));

		MvcResult result = mockMvc.perform(authed(get("/jobs/" + started.jobId() + "/timeline")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failedOperationCount").value(1))
				.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(body, "$.events[*].type"))
				.contains("OPERATION_FAILED", "JOB_FAILED");
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.type=='OPERATION_FAILED')].failureReason"))
				.containsExactly("FFmpeg exited non-zero");
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.type=='OPERATION_FAILED')].workerId"))
				.containsExactly("worker-a");
		assertThat((List<Number>) JsonPath.read(body, "$.events[?(@.type=='OPERATION_FAILED')].runtimeMs"))
				.containsExactly(11);
		assertNoSecrets(body);
		assertThat(body).doesNotContain("at com.example.drive", "InternalOperationService.java");
	}

	@Test
	void retryKeepsFailedAndCompletedAttempts() throws Exception {
		Started first = assignAndStart("METADATA");
		internalOperationService.fail(first.operationId(), new FailOperationRequest(first.attemptId(), 9L, "mpeg4 is not h264"));
		mockMvc.perform(authed(post("/jobs/" + first.jobId() + "/operations/" + first.operationId() + "/retry")))
				.andExpect(status().isOk());
		Started second = assignAndStart(first.jobId(), first.operationId(), "worker-b", "FIFO", "LEAST_LOADED");
		completeMetadata(second.operationId(), second.attemptId());

		MvcResult result = mockMvc.perform(authed(get("/jobs/" + first.jobId() + "/timeline")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andReturn();
		String body = result.getResponse().getContentAsString();
		List<String> types = JsonPath.read(body, "$.events[*].type");
		assertThat(types).containsSubsequence("OPERATION_FAILED", "OPERATION_RETRIED", "OPERATION_ASSIGNED",
				"OPERATION_STARTED", "OPERATION_COMPLETED");
		assertThat((List<Integer>) JsonPath.read(body, "$.events[?(@.type=='OPERATION_STARTED')].attemptNumber"))
				.containsExactly(1, 2);
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.type=='OPERATION_FAILED')].failureReason"))
				.containsExactly("mpeg4 is not h264");
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.outcome=='COMPLETED')].workerId"))
				.contains("worker-b");
		assertTimestampsNonDecreasing(body);
		assertThat((Integer) JsonPath.read(body, "$.operations[0].attemptCount")).isEqualTo(2);
	}

	@Test
	void cancellationUsesPersistedCompletedAt() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		mockMvc.perform(authed(post("/jobs/" + jobId + "/cancel")))
				.andExpect(status().isOk());

		MvcResult result = mockMvc.perform(authed(get("/jobs/" + jobId + "/timeline")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.cancelledOperationCount").value(1))
				.andReturn();
		String body = result.getResponse().getContentAsString();
		List<String> types = JsonPath.read(body, "$.events[*].type");
		assertThat(types).containsExactly("JOB_CREATED", "OPERATION_QUEUED", "OPERATION_CANCELLED", "JOB_CANCELLED");
		assertThat(types).doesNotContain("OPERATION_CANCEL_REQUESTED");
		assertThat(body).doesNotContain("\"attemptId\"");
	}

	@Test
	void runningCancellationShowsAttemptCancelledWithoutFabricatedRequestEvent() throws Exception {
		Started started = assignAndStart("METADATA");
		mockMvc.perform(authed(post("/jobs/" + started.jobId() + "/operations/" + started.operationId() + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationStatus").value("CANCEL_REQUESTED"));
		mockMvc.perform(post(
						"/internal/operations/" + started.operationId() + "/attempts/" + started.attemptId() + "/cancelled"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"workerId\":\"worker-a\",\"actualRuntimeMs\":15}"))
				.andExpect(status().isOk());

		MvcResult result = mockMvc.perform(authed(get("/jobs/" + started.jobId() + "/timeline")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andReturn();
		String body = result.getResponse().getContentAsString();
		List<String> types = JsonPath.read(body, "$.events[*].type");
		assertThat(types).contains("OPERATION_STARTED", "OPERATION_CANCELLED", "JOB_CANCELLED");
		assertThat(types).doesNotContain("OPERATION_CANCEL_REQUESTED");
		assertThat((List<String>) JsonPath.read(body, "$.events[?(@.type=='OPERATION_CANCELLED')].attemptId"))
				.containsExactly(started.attemptId().toString());
	}

	@Test
	void otherAccountReceivesJobNotFound() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		AuthTestSupport.TestAccount other = AuthTestSupport.createAccount(mockMvc, "other-" + UUID.randomUUID());
		mockMvc.perform(authed(get("/jobs/" + jobId + "/timeline"), other))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
		mockMvc.perform(get("/jobs/" + jobId + "/timeline"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void overlappingOperationsAreGloballyChronological() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"},{"type":"THUMBNAIL"}]}
				""");
		UUID metadataId = operationId(jobId, "METADATA");
		UUID thumbnailId = operationId(jobId, "THUMBNAIL");
		Started metadata = assignAndStart(jobId, metadataId, "worker-a", "FIFO", "LEXICOGRAPHIC");
		Started thumbnail = assignAndStart(jobId, thumbnailId, "worker-b", "FIFO", "LEAST_LOADED");
		completeThumbnail(jobId, thumbnail.operationId(), thumbnail.attemptId());
		completeMetadata(metadata.operationId(), metadata.attemptId());

		MvcResult result = mockMvc.perform(authed(get("/jobs/" + jobId + "/timeline")))
				.andExpect(status().isOk())
				.andReturn();
		String body = result.getResponse().getContentAsString();
		List<String> completedTypes = JsonPath.read(body, "$.events[?(@.type=='OPERATION_COMPLETED')].operationType");
		assertThat(completedTypes).containsExactly("THUMBNAIL", "METADATA");
		assertTimestampsNonDecreasing(body);
		List<String> assignedWorkers = JsonPath.read(body, "$.events[?(@.type=='OPERATION_ASSIGNED')].workerId");
		assertThat(assignedWorkers).containsExactly("worker-a", "worker-b");
	}

	@Test
	void incompleteTimestampsDoNotCrashOrInventDurations() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"METADATA"}]}
				""");
		MvcResult result = mockMvc.perform(authed(get("/jobs/" + jobId + "/timeline")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.durationMs").doesNotExist())
				.andExpect(jsonPath("$.completedAt").doesNotExist())
				.andExpect(jsonPath("$.operations[0].queueWaitMs").doesNotExist())
				.andExpect(jsonPath("$.operations[0].assignmentWaitMs").doesNotExist())
				.andExpect(jsonPath("$.operations[0].executionRuntimeMs").doesNotExist())
				.andExpect(jsonPath("$.operations[0].totalOperationLatencyMs").doesNotExist())
				.andReturn();
		List<String> types = JsonPath.read(result.getResponse().getContentAsString(), "$.events[*].type");
		assertThat(types).containsExactly("JOB_CREATED", "OPERATION_QUEUED");
	}

	private Started assignAndStart(String type) throws Exception {
		UUID jobId = createJob("{\"inputUri\":\"s3://media-input/video.mp4\",\"operations\":[{\"type\":\"" + type + "\"}]}");
		return assignAndStart(jobId, operationId(jobId, type), "worker-a", "FIFO", "LEXICOGRAPHIC");
	}

	private Started assignAndStart(UUID jobId, UUID operationId, String workerId, String operationPolicy, String workerPolicy)
			throws Exception {
		var assigned = schedulerService.assign(
				new AssignOperationRequest(operationId, workerId, operationPolicy, workerPolicy)
		);
		MvcResult result = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson(workerId, assigned.decisionId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andReturn();
		UUID attemptId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.attemptId"));
		return new Started(jobId, operationId, attemptId);
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

	private UUID operationId(UUID jobId, String type) {
		return UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ? and operation_type = ?",
				String.class,
				jobId,
				type
		));
	}

	private Instant updatedAt(UUID jobId) {
		Timestamp value = jdbcTemplate.queryForObject(
				"select updated_at from jobs where id = ?",
				Timestamp.class,
				jobId
		);
		return value.toInstant();
	}

	private static void assertTimestampsNonDecreasing(String body) {
		List<String> timestamps = JsonPath.read(body, "$.events[*].timestamp");
		Instant previous = Instant.MIN;
		for (String timestamp : timestamps) {
			Instant current = Instant.parse(timestamp);
			assertThat(current).isAfterOrEqualTo(previous);
			previous = current;
		}
	}

	private static void assertNoSecrets(String body) {
		assertThat(body).doesNotContain(
				"mp_live_",
				"mp_wk_",
				"X-Amz-Signature",
				"X-Amz-Credential",
				"minioadmin",
				"amqp://",
				"Authorization",
				"worker_pepper",
				"scheduler_token"
		);
	}

	private record Started(UUID jobId, UUID operationId, UUID attemptId) {
	}
}
