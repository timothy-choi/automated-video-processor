package com.example.drive.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.job.dto.ClaimedOperationResponse;
import com.example.drive.support.ControlServiceTest;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
class InternalOperationApiIntegrationTest {

	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private InternalOperationService internalOperationService;

	@BeforeEach
	void clearTables() {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
	}

	@Test
	void claimMovesMetadataOperationAndJobToRunning() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/sample.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");

		MvcResult result = mockMvc.perform(post("/internal/operations/claim"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(jobId.toString()))
				.andExpect(jsonPath("$.type").value("METADATA"))
				.andExpect(jsonPath("$.inputUri").value("file:///tmp/sample.mp4"))
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.operationId").isString())
				.andReturn();

		UUID operationId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.operationId"));

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.operations[0].status").value("RUNNING"));

		Integer runningCount = jdbcTemplate.queryForObject(
				"select count(*) from operations where id = ? and status = 'RUNNING' and started_at is not null",
				Integer.class,
				operationId
		);
		assertThat(runningCount).isEqualTo(1);
	}

	@Test
	void claimAcceptsMetadataWithS3Uri() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");

		mockMvc.perform(post("/internal/operations/claim"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(jobId.toString()))
				.andExpect(jsonPath("$.type").value("METADATA"))
				.andExpect(jsonPath("$.inputUri").value("s3://media-input/video.mp4"))
				.andExpect(jsonPath("$.status").value("RUNNING"));
	}

	@Test
	void claimAcceptsThumbnailWithFileUri() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/thumb.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");

		mockMvc.perform(post("/internal/operations/claim"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("THUMBNAIL"))
				.andExpect(jsonPath("$.inputUri").value("file:///tmp/thumb.mp4"))
				.andExpect(jsonPath("$.status").value("RUNNING"));
	}

	@Test
	void claimAcceptsThumbnailWithS3Uri() throws Exception {
		createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");

		mockMvc.perform(post("/internal/operations/claim"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("THUMBNAIL"))
				.andExpect(jsonPath("$.inputUri").value("s3://media-input/video.mp4"));
	}

	@Test
	void claimReturns204WhenNoWork() throws Exception {
		mockMvc.perform(post("/internal/operations/claim"))
				.andExpect(status().isNoContent());
	}

	@Test
	void claimIgnoresUnsupportedQueuedOperations() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/transcode.mp4",
				  "operations": [{"type": "TRANSCODE_1080P"}]
				}
				""");

		mockMvc.perform(post("/internal/operations/claim"))
				.andExpect(status().isNoContent());
	}

	@Test
	void concurrentClaimsCannotReceiveTheSameOperation() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/race.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");

		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Optional<ClaimedOperationResponse>>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(pool.submit(() -> {
					start.await(5, TimeUnit.SECONDS);
					return internalOperationService.claimNextExecutableOperation();
				}));
			}
			start.countDown();

			List<Optional<ClaimedOperationResponse>> results = new ArrayList<>();
			for (Future<Optional<ClaimedOperationResponse>> future : futures) {
				results.add(future.get(10, TimeUnit.SECONDS));
			}

			List<UUID> claimedIds = results.stream()
					.flatMap(Optional::stream)
					.map(ClaimedOperationResponse::operationId)
					.toList();
			assertThat(claimedIds).hasSize(1);
			assertThat(results.stream().filter(Optional::isEmpty).count()).isEqualTo(1);
		}
	}

	@Test
	void completePersistsRuntimeAndMetadataResultAndCompletesJob() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/complete.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		UUID operationId = claimOperationId();

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "actualRuntimeMs": 42,
								  "metadata": {
								    "durationSeconds": 2.0,
								    "formatName": "mov,mp4,m4a,3gp,3g2,mj2",
								    "sizeBytes": 1234,
								    "videoCodec": "h264",
								    "width": 320,
								    "height": 240,
								    "frameRate": 30.0
								  }
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.actualRuntimeMs").value(42))
				.andExpect(jsonPath("$.result.videoCodec").value("h264"))
				.andExpect(jsonPath("$.result.width").value(320))
				.andExpect(jsonPath("$.completedAt").isString());

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		Integer stored = jdbcTemplate.queryForObject(
				"select count(*) from operations where id = ? and status = 'COMPLETED' and actual_runtime_ms = 42 and result_json is not null",
				Integer.class,
				operationId
		);
		assertThat(stored).isEqualTo(1);
	}

	@Test
	void completeThumbnailPersistsArtifactAndCompletesJob() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");
		UUID operationId = claimOperationId();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg";

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(thumbnailCompleteJson(objectUri, 1234)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.actualRuntimeMs").value(20))
				.andExpect(jsonPath("$.result").doesNotExist());

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		mockMvc.perform(get("/jobs/" + jobId + "/artifacts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(1))
				.andExpect(jsonPath("$.artifacts[0].operationId").value(operationId.toString()))
				.andExpect(jsonPath("$.artifacts[0].type").value("THUMBNAIL"))
				.andExpect(jsonPath("$.artifacts[0].objectUri").value(objectUri))
				.andExpect(jsonPath("$.artifacts[0].contentType").value("image/jpeg"))
				.andExpect(jsonPath("$.artifacts[0].sizeBytes").value(1234))
				.andExpect(jsonPath("$.artifacts[0].checksum").value(SHA256));

		UUID storedJobId = UUID.fromString(jdbcTemplate.queryForObject(
				"select job_id from artifacts where operation_id = ?",
				String.class,
				operationId
		));
		assertThat(storedJobId).isEqualTo(jobId);
		Integer blobColumns = jdbcTemplate.queryForObject(
				"select count(*) from information_schema.columns where table_name = 'artifacts' and data_type in ('bytea', 'oid')",
				Integer.class
		);
		assertThat(blobColumns).isZero();
	}

	@Test
	void failPersistsReasonAndFailsJob() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///missing.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		UUID operationId = claimOperationId();

		mockMvc.perform(post("/internal/operations/" + operationId + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "actualRuntimeMs": 15,
								  "reason": "ffprobe: No such file or directory"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failureReason").value("ffprobe: No such file or directory"))
				.andExpect(jsonPath("$.actualRuntimeMs").value(15));

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));
	}

	@Test
	void failedThumbnailMarksJobFailed() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "THUMBNAIL"}
				  ]
				}
				""");
		UUID metadataId = claimOperationId();
		completeMetadata(metadataId);
		UUID thumbnailId = claimOperationId();

		mockMvc.perform(post("/internal/operations/" + thumbnailId + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reason": "ffmpeg failed: no video stream"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));
	}

	@Test
	void completingQueuedOperationIsRejected() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/queued.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		UUID operationId = UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ?",
				String.class,
				jobId
		));

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "actualRuntimeMs": 1,
								  "metadata": {"formatName": "mp4"}
								}
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"));
	}

	@Test
	void duplicateCompletionIsIdempotent() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/idempotent.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		UUID operationId = claimOperationId();
		String body = """
				{
				  "actualRuntimeMs": 10,
				  "metadata": {"formatName": "mp4", "width": 320}
				}
				""";

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.result.width").value(320));
	}

	@Test
	void duplicateThumbnailCompletionDoesNotCreateSecondArtifact() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/thumb-idempotent.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");
		UUID operationId = claimOperationId();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg";
		String body = thumbnailCompleteJson(objectUri, 99);

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		Integer count = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				operationId
		);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void completingFailedOperationIsRejected() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/conflict.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		UUID operationId = claimOperationId();
		mockMvc.perform(post("/internal/operations/" + operationId + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reason": "probe failed"}
								"""))
				.andExpect(status().isOk());

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "actualRuntimeMs": 1,
								  "metadata": {"formatName": "mp4"}
								}
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"));
	}

	@Test
	void metadataAndThumbnailCompleteJob() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/mixed.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "THUMBNAIL"}
				  ]
				}
				""");
		UUID metadataId = claimOperationId();
		completeMetadata(metadataId);

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"));

		UUID thumbnailId = claimOperationId();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + thumbnailId + "/thumbnail.jpg";
		mockMvc.perform(post("/internal/operations/" + thumbnailId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(thumbnailCompleteJson(objectUri, 50)))
				.andExpect(status().isOk());

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		mockMvc.perform(get("/jobs/" + jobId + "/artifacts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(1))
				.andExpect(jsonPath("$.artifacts[0].operationId").value(thumbnailId.toString()));
	}

	@Test
	void jobStaysNotCompletedWhenUnsupportedOperationsRemain() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/mixed.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "TRANSCODE_1080P"}
				  ]
				}
				""");
		UUID operationId = claimOperationId();
		completeMetadata(operationId);

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.operations[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.operations[1].status").value("QUEUED"));
	}

	@Test
	void unknownOperationReturns404() throws Exception {
		mockMvc.perform(post("/internal/operations/" + UUID.randomUUID() + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reason": "missing"}
								"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
	}

	@Test
	void startFromAssignedIsConditionalAndDuplicateSafe() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/start.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		UUID operationId = UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ?",
				String.class,
				jobId
		));
		jdbcTemplate.update("update operations set status = 'ASSIGNED' where id = ?", operationId);

		mockMvc.perform(post("/internal/operations/" + operationId + "/start"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andExpect(jsonPath("$.status").value("RUNNING"));
		mockMvc.perform(post("/internal/operations/" + operationId + "/start"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ALREADY_RUNNING"));

		completeMetadata(operationId);
		mockMvc.perform(post("/internal/operations/" + operationId + "/start"))
				.andExpect(jsonPath("$.outcome").value("ALREADY_TERMINAL"));
	}

	@Test
	void startUnknownOperationReturns404() throws Exception {
		mockMvc.perform(post("/internal/operations/" + UUID.randomUUID() + "/start"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
	}

	private UUID createJob(String json) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}

	private UUID claimOperationId() throws Exception {
		MvcResult result = mockMvc.perform(post("/internal/operations/claim"))
				.andExpect(status().isOk())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.operationId"));
	}

	private void completeMetadata(UUID operationId) throws Exception {
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "actualRuntimeMs": 8,
								  "metadata": {"formatName": "mp4"}
								}
								"""))
				.andExpect(status().isOk());
	}

	private static String thumbnailCompleteJson(String objectUri, int sizeBytes) {
		return """
				{
				  "actualRuntimeMs": 20,
				  "artifact": {
				    "objectUri": "%s",
				    "contentType": "image/jpeg",
				    "sizeBytes": %d,
				    "checksum": "%s"
				  }
				}
				""".formatted(objectUri, sizeBytes, SHA256);
	}
}
