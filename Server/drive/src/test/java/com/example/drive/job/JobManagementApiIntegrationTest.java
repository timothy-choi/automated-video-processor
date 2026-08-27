package com.example.drive.job;

import com.example.drive.support.AuthenticatedApiTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.job.dto.FailOperationRequest;
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
class JobManagementApiIntegrationTest extends AuthenticatedApiTest {

	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Autowired
	private MockMvc mockMvc;

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
		WorkerTestSupport.register(
				mockMvc,
				"worker-a",
				"METADATA",
				"THUMBNAIL",
				"AUDIO_EXTRACTION",
				"TRANSCODE_1080P",
				"H264_TO_AV1"
		);
	}

	@Test
	void listDefaultsToEmptyPageMetadata() throws Exception {
		mockMvc.perform(authed(get("/jobs")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.totalPages").value(0));
	}

	@Test
	void listPaginatesAcrossPagesWithTotals() throws Exception {
		for (int i = 0; i < 25; i++) {
			createJob("METADATA", "NORMAL");
		}

		MvcResult page0 = mockMvc.perform(authed(get("/jobs")).param("page", "0").param("size", "20"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(20))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(25))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andReturn();

		MvcResult page1 = mockMvc.perform(authed(get("/jobs")).param("page", "1").param("size", "20"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(5))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(25))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andReturn();

		List<String> first = JsonPath.read(page0.getResponse().getContentAsString(), "$.items[*].id");
		List<String> second = JsonPath.read(page1.getResponse().getContentAsString(), "$.items[*].id");
		assertThat(first).hasSize(20).doesNotContainAnyElementsOf(second);
		assertThat(second).hasSize(5);
	}

	@Test
	void listDefaultsToNewestCreatedAtFirst() throws Exception {
		UUID older = createJob("METADATA", "NORMAL");
		UUID newer = createJob("THUMBNAIL", "NORMAL");
		setCreatedAt(older, Instant.parse("2026-08-01T00:00:00Z"));
		setCreatedAt(newer, Instant.parse("2026-08-20T00:00:00Z"));

		mockMvc.perform(authed(get("/jobs")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].id").value(newer.toString()))
				.andExpect(jsonPath("$.items[1].id").value(older.toString()))
				.andExpect(jsonPath("$.items[0].operations").doesNotExist())
				.andExpect(jsonPath("$.items[0].failureReason").doesNotExist());
	}

	@Test
	void listTieBreaksOnIdDescendingWhenCreatedAtMatches() throws Exception {
		UUID first = createJob("METADATA", "NORMAL");
		UUID second = createJob("THUMBNAIL", "NORMAL");
		Instant same = Instant.parse("2026-08-15T12:00:00Z");
		setCreatedAt(first, same);
		setCreatedAt(second, same);

		// PostgreSQL UUID ORDER BY matches canonical hex string order, not Java UUID.compareTo.
		UUID expectedFirst = first.toString().compareTo(second.toString()) > 0 ? first : second;
		UUID expectedSecond = expectedFirst.equals(first) ? second : first;

		mockMvc.perform(authed(get("/jobs")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(expectedFirst.toString()))
				.andExpect(jsonPath("$.items[1].id").value(expectedSecond.toString()));
	}

	@Test
	void listSortByUpdatedAtAscending() throws Exception {
		UUID laterUpdate = createJob("METADATA", "NORMAL");
		UUID earlierUpdate = createJob("THUMBNAIL", "NORMAL");
		setUpdatedAt(earlierUpdate, Instant.parse("2026-08-01T00:00:00Z"));
		setUpdatedAt(laterUpdate, Instant.parse("2026-08-20T00:00:00Z"));

		mockMvc.perform(authed(get("/jobs")).param("sort", "updatedAt").param("direction", "asc"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(earlierUpdate.toString()))
				.andExpect(jsonPath("$.items[1].id").value(laterUpdate.toString()));
	}

	@Test
	void listFiltersByStatus() throws Exception {
		UUID queued = createJob("METADATA", "NORMAL");
		UUID failed = createJob("THUMBNAIL", "NORMAL");
		UUID completed = createJob("AUDIO_EXTRACTION", "NORMAL");
		UUID cancelled = createJob("TRANSCODE_1080P", "NORMAL");
		setStatus(failed, "FAILED");
		setStatus(completed, "COMPLETED");
		setStatus(cancelled, "CANCELLED");

		mockMvc.perform(authed(get("/jobs")).param("status", "FAILED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(failed.toString()))
				.andExpect(jsonPath("$.items[0].status").value("FAILED"));

		mockMvc.perform(authed(get("/jobs")).param("status", "COMPLETED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(completed.toString()));

		mockMvc.perform(authed(get("/jobs")).param("status", "CANCELLED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(cancelled.toString()));

		mockMvc.perform(authed(get("/jobs")).param("status", "QUEUED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(queued.toString()));
	}

	@Test
	void listFiltersByOperationTypeWithoutDuplicateRows() throws Exception {
		UUID av1 = createJob("""
				{"inputUri":"s3://media-input/av1.mp4","operations":[
				  {"type":"METADATA"},
				  {"type":"H264_TO_AV1"},
				  {"type":"THUMBNAIL"}
				]}
				""");
		createJob("METADATA", "NORMAL");
		createJob("TRANSCODE_1080P", "NORMAL");

		mockMvc.perform(authed(get("/jobs")).param("operationType", "H264_TO_AV1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].id").value(av1.toString()))
				.andExpect(jsonPath("$.items[0].operationCount").value(3));
	}

	@Test
	void listFiltersByPriority() throws Exception {
		UUID high = createJob("METADATA", "HIGH");
		createJob("METADATA", "NORMAL");
		createJob("METADATA", "LOW");

		mockMvc.perform(authed(get("/jobs")).param("priority", "HIGH"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(high.toString()))
				.andExpect(jsonPath("$.items[0].priority").value("HIGH"));
	}

	@Test
	void listFiltersByCreatedDateRange() throws Exception {
		UUID early = createJob("METADATA", "NORMAL");
		UUID mid = createJob("THUMBNAIL", "NORMAL");
		UUID late = createJob("AUDIO_EXTRACTION", "NORMAL");
		setCreatedAt(early, Instant.parse("2026-08-01T00:00:00Z"));
		setCreatedAt(mid, Instant.parse("2026-08-15T12:00:00Z"));
		setCreatedAt(late, Instant.parse("2026-08-31T23:59:59Z"));

		mockMvc.perform(authed(get("/jobs")).param("createdAfter", "2026-08-15T12:00:00Z"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].id").value(late.toString()))
				.andExpect(jsonPath("$.items[1].id").value(mid.toString()));

		mockMvc.perform(authed(get("/jobs")).param("createdBefore", "2026-08-15T12:00:00Z"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].id").value(mid.toString()))
				.andExpect(jsonPath("$.items[1].id").value(early.toString()));

		mockMvc.perform(authed(get("/jobs"))
						.param("createdAfter", "2026-08-15T12:00:00Z")
						.param("createdBefore", "2026-08-31T23:59:59Z"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].id").value(late.toString()))
				.andExpect(jsonPath("$.items[1].id").value(mid.toString()));
	}

	@Test
	void listCombinesFiltersWithAndSemantics() throws Exception {
		UUID match = createJob("""
				{"inputUri":"s3://media-input/match.mp4","priority":"HIGH","operations":[{"type":"H264_TO_AV1"}]}
				""");
		UUID wrongStatus = createJob("""
				{"inputUri":"s3://media-input/completed.mp4","priority":"HIGH","operations":[{"type":"H264_TO_AV1"}]}
				""");
		UUID wrongType = createJob("""
				{"inputUri":"s3://media-input/meta.mp4","priority":"HIGH","operations":[{"type":"METADATA"}]}
				""");
		UUID wrongPriority = createJob("""
				{"inputUri":"s3://media-input/normal.mp4","priority":"NORMAL","operations":[{"type":"H264_TO_AV1"}]}
				""");
		setStatus(match, "FAILED");
		setStatus(wrongStatus, "COMPLETED");
		setStatus(wrongType, "FAILED");
		setStatus(wrongPriority, "FAILED");

		mockMvc.perform(authed(get("/jobs"))
						.param("status", "FAILED")
						.param("operationType", "H264_TO_AV1")
						.param("priority", "HIGH"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(match.toString()));
	}

	@Test
	void invalidFiltersReturn400() throws Exception {
		mockMvc.perform(authed(get("/jobs")).param("status", "NOT_A_STATUS"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"));

		mockMvc.perform(authed(get("/jobs")).param("operationType", "TRANSCODE_4K_TO_1080P"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"));

		mockMvc.perform(authed(get("/jobs")).param("priority", "URGENT"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"));

		mockMvc.perform(authed(get("/jobs")).param("sort", "priority"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mockMvc.perform(authed(get("/jobs")).param("direction", "sideways"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mockMvc.perform(authed(get("/jobs"))
						.param("createdAfter", "2026-08-31T00:00:00Z")
						.param("createdBefore", "2026-08-01T00:00:00Z"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void invalidPaginationReturns400() throws Exception {
		mockMvc.perform(authed(get("/jobs")).param("page", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mockMvc.perform(authed(get("/jobs")).param("size", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mockMvc.perform(authed(get("/jobs")).param("size", "-5"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mockMvc.perform(authed(get("/jobs")).param("size", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void retryRemovesJobFromFailedListing() throws Exception {
		UUID jobId = createJob("METADATA", "NORMAL");
		UUID operationId = operationId(jobId, "METADATA");
		UUID attemptId = assignAndStart(operationId);
		internalOperationService.fail(operationId, new FailOperationRequest(attemptId, 11L, "probe failed"));

		mockMvc.perform(authed(get("/jobs")).param("status", "FAILED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(jobId.toString()));

		mockMvc.perform(authed(post("/jobs/" + jobId + "/operations/" + operationId + "/retry")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobStatus").value("QUEUED"));

		mockMvc.perform(authed(get("/jobs")).param("status", "FAILED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));

		mockMvc.perform(authed(get("/jobs")).param("status", "QUEUED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(jobId.toString()));
	}

	@Test
	void cancelAppearsInCancelledListing() throws Exception {
		UUID jobId = createJob("METADATA", "NORMAL");

		mockMvc.perform(authed(post("/jobs/" + jobId + "/cancel")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		mockMvc.perform(authed(get("/jobs")).param("status", "CANCELLED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(jobId.toString()));

		mockMvc.perform(authed(get("/jobs")).param("status", "QUEUED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));

		mockMvc.perform(authed(get("/jobs")).param("status", "RUNNING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void jobDetailIncludesCountsWithoutInliningArtifacts() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/detail.mp4","operations":[
				  {"type":"METADATA"},
				  {"type":"THUMBNAIL"}
				]}
				""");
		UUID operationId = operationId(jobId, "THUMBNAIL");
		insertArtifact(jobId, operationId);

		mockMvc.perform(authed(get("/jobs/" + jobId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(jobId.toString()))
				.andExpect(jsonPath("$.operationCount").value(2))
				.andExpect(jsonPath("$.artifactCount").value(1))
				.andExpect(jsonPath("$.operations.length()").value(2))
				.andExpect(jsonPath("$.artifacts").doesNotExist());
	}

	@Test
	void artifactByIdReturnsMetadataForOwningJob() throws Exception {
		UUID jobId = createJob("THUMBNAIL", "NORMAL");
		UUID operationId = operationId(jobId, "THUMBNAIL");
		UUID artifactId = insertArtifact(jobId, operationId);
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg";

		mockMvc.perform(authed(get("/jobs/" + jobId + "/artifacts/" + artifactId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(artifactId.toString()))
				.andExpect(jsonPath("$.operationId").value(operationId.toString()))
				.andExpect(jsonPath("$.type").value("THUMBNAIL"))
				.andExpect(jsonPath("$.objectUri").value(objectUri))
				.andExpect(jsonPath("$.contentType").value("image/jpeg"))
				.andExpect(jsonPath("$.sizeBytes").value(1234))
				.andExpect(jsonPath("$.checksum").value(SHA256))
				.andExpect(jsonPath("$.createdAt").isString())
				.andExpect(jsonPath("$.accessKey").doesNotExist())
				.andExpect(jsonPath("$.secretKey").doesNotExist())
				.andExpect(jsonPath("$.credentials").doesNotExist());

		mockMvc.perform(authed(get("/jobs/" + jobId + "/artifacts")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(1))
				.andExpect(jsonPath("$.artifacts[0].id").value(artifactId.toString()))
				.andExpect(jsonPath("$.artifacts[0].objectUri").value(objectUri));
	}

	@Test
	void artifactByIdRejectsUnknownJobUnknownArtifactAndWrongOwner() throws Exception {
		UUID jobA = createJob("THUMBNAIL", "NORMAL");
		UUID jobB = createJob("METADATA", "NORMAL");
		UUID artifactA = insertArtifact(jobA, operationId(jobA, "THUMBNAIL"));
		UUID missingJob = UUID.fromString("99999999-9999-9999-9999-999999999999");
		UUID missingArtifact = UUID.fromString("88888888-8888-8888-8888-888888888888");

		mockMvc.perform(authed(get("/jobs/" + missingJob + "/artifacts/" + artifactA)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

		mockMvc.perform(authed(get("/jobs/" + jobA + "/artifacts/" + missingArtifact)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ARTIFACT_NOT_FOUND"));

		mockMvc.perform(authed(get("/jobs/" + jobB + "/artifacts/" + artifactA)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ARTIFACT_NOT_FOUND"));
	}

	@Test
	void listSummaryIncludesCounts() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/counts.mp4","operations":[
				  {"type":"METADATA"},
				  {"type":"THUMBNAIL"}
				]}
				""");
		insertArtifact(jobId, operationId(jobId, "THUMBNAIL"));

		mockMvc.perform(authed(get("/jobs")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(jobId.toString()))
				.andExpect(jsonPath("$.items[0].operationCount").value(2))
				.andExpect(jsonPath("$.items[0].artifactCount").value(1))
				.andExpect(jsonPath("$.items[0].inputUri").value("s3://media-input/counts.mp4"))
				.andExpect(jsonPath("$.items[0].status").value("QUEUED"));
	}

	private UUID createJob(String type, String priority) {
		return createJob("""
				{"inputUri":"s3://media-input/%s.mp4","priority":"%s","operations":[{"type":"%s"}]}
				""".formatted(UUID.randomUUID(), priority, type));
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

	private UUID assignAndStart(UUID operationId) throws Exception {
		var assigned = schedulerService.assign(
				new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC")
		);
		MvcResult result = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-a", assigned.decisionId())))
				.andExpect(status().isOk())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.attemptId"));
	}

	private UUID operationId(UUID jobId, String type) {
		return UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ? and operation_type = ?",
				String.class,
				jobId,
				type
		));
	}

	private void setStatus(UUID jobId, String status) {
		jdbcTemplate.update("update jobs set status = ? where id = ?", status, jobId);
	}

	private void setCreatedAt(UUID jobId, Instant createdAt) {
		jdbcTemplate.update(
				"update jobs set created_at = ? where id = ?",
				Timestamp.from(createdAt),
				jobId
		);
	}

	private void setUpdatedAt(UUID jobId, Instant updatedAt) {
		jdbcTemplate.update(
				"update jobs set updated_at = ? where id = ?",
				Timestamp.from(updatedAt),
				jobId
		);
	}

	private UUID insertArtifact(UUID jobId, UUID operationId) {
		UUID artifactId = UUID.randomUUID();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg";
		jdbcTemplate.update("""
						insert into artifacts (
						  id, job_id, operation_id, artifact_type, object_uri, content_type, size_bytes, checksum, created_at
						) values (?, ?, ?, 'THUMBNAIL', ?, 'image/jpeg', 1234, ?, ?)
						""",
				artifactId,
				jobId,
				operationId,
				objectUri,
				SHA256,
				Timestamp.from(Instant.parse("2026-08-20T00:00:00Z"))
		);
		return artifactId;
	}
}
