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
import com.example.drive.support.WorkerTestSupport;
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
	void clearTables() throws Exception {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
		jdbcTemplate.execute("delete from worker_supported_codecs");
		jdbcTemplate.execute("delete from worker_supported_operations");
		jdbcTemplate.execute("delete from workers");
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P");
	}

	@Test
	void claimMovesMetadataOperationAndJobToRunning() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/sample.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");

		MvcResult result = mockMvc.perform(post("/internal/operations/claim")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(jobId.toString()))
				.andExpect(jsonPath("$.type").value("METADATA"))
				.andExpect(jsonPath("$.inputUri").value("file:///tmp/sample.mp4"))
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.operationId").isString())
				.andExpect(jsonPath("$.attemptId").isString())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.leaseExpiresAt").isString())
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
		Integer attempts = jdbcTemplate.queryForObject(
				"select count(*) from execution_attempts where operation_id = ? and status = 'RUNNING' and attempt_number = 1",
				Integer.class,
				operationId
		);
		assertThat(attempts).isEqualTo(1);
	}

	@Test
	void claimAcceptsMetadataWithS3Uri() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");

		mockMvc.perform(post("/internal/operations/claim")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
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

		mockMvc.perform(post("/internal/operations/claim")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
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

		mockMvc.perform(post("/internal/operations/claim")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("THUMBNAIL"))
				.andExpect(jsonPath("$.inputUri").value("s3://media-input/video.mp4"));
	}

	@Test
	void claimReturns204WhenNoWork() throws Exception {
		mockMvc.perform(post("/internal/operations/claim")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isNoContent());
	}

	@Test
	void claimIgnoresUnsupportedQueuedOperations() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/transcode.mp4",
				  "operations": [{"type": "H264_TO_AV1"}]
				}
				""");

		mockMvc.perform(post("/internal/operations/claim")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
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
					return internalOperationService.claimNextExecutableOperation("worker-a");
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
		ClaimedIds claimed = claimOperation();

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "attemptId": "%s",
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
								""".formatted(claimed.attemptId())))
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
				claimed.operationId()
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
		ClaimedIds claimed = claimOperation();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + claimed.operationId() + "/thumbnail.jpg";

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(thumbnailCompleteJson(claimed.attemptId(), objectUri, 1234)))
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
				.andExpect(jsonPath("$.artifacts[0].operationId").value(claimed.operationId().toString()))
				.andExpect(jsonPath("$.artifacts[0].type").value("THUMBNAIL"))
				.andExpect(jsonPath("$.artifacts[0].objectUri").value(objectUri))
				.andExpect(jsonPath("$.artifacts[0].contentType").value("image/jpeg"))
				.andExpect(jsonPath("$.artifacts[0].sizeBytes").value(1234))
				.andExpect(jsonPath("$.artifacts[0].checksum").value(SHA256));

		UUID storedJobId = UUID.fromString(jdbcTemplate.queryForObject(
				"select job_id from artifacts where operation_id = ?",
				String.class,
				claimed.operationId()
		));
		assertThat(storedJobId).isEqualTo(jobId);
		Integer blobColumns = jdbcTemplate.queryForObject(
				"select count(*) from information_schema.columns where table_name = 'artifacts' and data_type in ('bytea', 'oid')",
				Integer.class
		);
		assertThat(blobColumns).isZero();
	}

	@Test
	void completeAudioExtractionPersistsArtifactAndCompletesJob() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "AUDIO_EXTRACTION"}]
				}
				""");
		ClaimedIds claimed = claimOperation();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + claimed.operationId() + "/audio.m4a";

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(audioCompleteJson(claimed.attemptId(), objectUri, 4096)))
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
				.andExpect(jsonPath("$.artifacts[0].operationId").value(claimed.operationId().toString()))
				.andExpect(jsonPath("$.artifacts[0].type").value("AUDIO"))
				.andExpect(jsonPath("$.artifacts[0].objectUri").value(objectUri))
				.andExpect(jsonPath("$.artifacts[0].contentType").value("audio/mp4"))
				.andExpect(jsonPath("$.artifacts[0].sizeBytes").value(4096))
				.andExpect(jsonPath("$.artifacts[0].checksum").value(SHA256));

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
		ClaimedIds claimed = claimOperation();

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "attemptId": "%s",
								  "actualRuntimeMs": 15,
								  "reason": "ffprobe: No such file or directory"
								}
								""".formatted(claimed.attemptId())))
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
		ClaimedIds metadata = claimOperation();
		completeMetadata(metadata.operationId(), metadata.attemptId());
		ClaimedIds thumbnail = claimOperation();

		mockMvc.perform(post("/internal/operations/" + thumbnail.operationId() + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"attemptId": "%s", "reason": "ffmpeg failed: no video stream"}
								""".formatted(thumbnail.attemptId())))
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
								  "attemptId": "%s",
								  "actualRuntimeMs": 1,
								  "metadata": {"formatName": "mp4"}
								}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"));
	}

	@Test
	void duplicateCompletionIsIdempotent() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/idempotent.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		ClaimedIds claimed = claimOperation();
		String body = """
				{
				  "attemptId": "%s",
				  "actualRuntimeMs": 10,
				  "metadata": {"formatName": "mp4", "width": 320}
				}
				""".formatted(claimed.attemptId());

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
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
		ClaimedIds claimed = claimOperation();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + claimed.operationId() + "/thumbnail.jpg";
		String body = thumbnailCompleteJson(claimed.attemptId(), objectUri, 99);

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());
		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		Integer count = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				claimed.operationId()
		);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void duplicateAudioCompletionDoesNotCreateSecondArtifact() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/audio-idempotent.mp4",
				  "operations": [{"type": "AUDIO_EXTRACTION"}]
				}
				""");
		ClaimedIds claimed = claimOperation();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + claimed.operationId() + "/audio.m4a";
		String body = audioCompleteJson(claimed.attemptId(), objectUri, 99);

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());
		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		Integer count = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				claimed.operationId()
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
		ClaimedIds claimed = claimOperation();
		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"attemptId": "%s", "reason": "probe failed"}
								""".formatted(claimed.attemptId())))
				.andExpect(status().isOk());

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "attemptId": "%s",
								  "actualRuntimeMs": 1,
								  "metadata": {"formatName": "mp4"}
								}
								""".formatted(claimed.attemptId())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_EXECUTION_ATTEMPT"));
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
		ClaimedIds metadata = claimOperation();
		completeMetadata(metadata.operationId(), metadata.attemptId());

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"));

		ClaimedIds thumbnail = claimOperation();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + thumbnail.operationId() + "/thumbnail.jpg";
		mockMvc.perform(post("/internal/operations/" + thumbnail.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(thumbnailCompleteJson(thumbnail.attemptId(), objectUri, 50)))
				.andExpect(status().isOk());

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		mockMvc.perform(get("/jobs/" + jobId + "/artifacts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(1))
				.andExpect(jsonPath("$.artifacts[0].operationId").value(thumbnail.operationId().toString()));
	}

	@Test
	void mixedMetadataThumbnailAndAudioCompleteJob() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/mixed.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "THUMBNAIL"},
				    {"type": "AUDIO_EXTRACTION"}
				  ]
				}
				""");
		ClaimedIds metadata = claimOperation();
		completeMetadata(metadata.operationId(), metadata.attemptId());
		ClaimedIds thumbnail = claimOperation();
		String thumbUri = "s3://media-output/jobs/" + jobId + "/operations/" + thumbnail.operationId() + "/thumbnail.jpg";
		mockMvc.perform(post("/internal/operations/" + thumbnail.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(thumbnailCompleteJson(thumbnail.attemptId(), thumbUri, 50)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"));
		ClaimedIds audio = claimOperation();
		String audioUri = "s3://media-output/jobs/" + jobId + "/operations/" + audio.operationId() + "/audio.m4a";
		mockMvc.perform(post("/internal/operations/" + audio.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(audioCompleteJson(audio.attemptId(), audioUri, 80)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		mockMvc.perform(get("/jobs/" + jobId + "/artifacts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(2));
	}

	@Test
	void completeTranscode1080pPersistsArtifactAndCompletesJob() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "TRANSCODE_1080P"}]
				}
				""");
		ClaimedIds claimed = claimOperation();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + claimed.operationId() + "/video-1080p.mp4";

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(transcodeCompleteJson(claimed.attemptId(), objectUri, 8192)))
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
				.andExpect(jsonPath("$.artifacts[0].operationId").value(claimed.operationId().toString()))
				.andExpect(jsonPath("$.artifacts[0].type").value("TRANSCODE_1080P"))
				.andExpect(jsonPath("$.artifacts[0].objectUri").value(objectUri))
				.andExpect(jsonPath("$.artifacts[0].contentType").value("video/mp4"))
				.andExpect(jsonPath("$.artifacts[0].sizeBytes").value(8192))
				.andExpect(jsonPath("$.artifacts[0].checksum").value(SHA256));

		Integer blobColumns = jdbcTemplate.queryForObject(
				"select count(*) from information_schema.columns where table_name = 'artifacts' and data_type in ('bytea', 'oid')",
				Integer.class
		);
		assertThat(blobColumns).isZero();
	}

	@Test
	void duplicateTranscodeCompletionDoesNotCreateSecondArtifact() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/transcode-idempotent.mp4",
				  "operations": [{"type": "TRANSCODE_1080P"}]
				}
				""");
		ClaimedIds claimed = claimOperation();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + claimed.operationId() + "/video-1080p.mp4";
		String body = transcodeCompleteJson(claimed.attemptId(), objectUri, 99);

		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());
		mockMvc.perform(post("/internal/operations/" + claimed.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		Integer count = jdbcTemplate.queryForObject(
				"select count(*) from artifacts where operation_id = ?",
				Integer.class,
				claimed.operationId()
		);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void mixedMetadataThumbnailAudioAndTranscodeCompleteJob() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/mixed.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "THUMBNAIL"},
				    {"type": "AUDIO_EXTRACTION"},
				    {"type": "TRANSCODE_1080P"}
				  ]
				}
				""");
		ClaimedIds metadata = claimOperation();
		completeMetadata(metadata.operationId(), metadata.attemptId());
		ClaimedIds thumbnail = claimOperation();
		String thumbUri = "s3://media-output/jobs/" + jobId + "/operations/" + thumbnail.operationId() + "/thumbnail.jpg";
		mockMvc.perform(post("/internal/operations/" + thumbnail.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(thumbnailCompleteJson(thumbnail.attemptId(), thumbUri, 50)))
				.andExpect(status().isOk());
		ClaimedIds audio = claimOperation();
		String audioUri = "s3://media-output/jobs/" + jobId + "/operations/" + audio.operationId() + "/audio.m4a";
		mockMvc.perform(post("/internal/operations/" + audio.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(audioCompleteJson(audio.attemptId(), audioUri, 80)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"));
		ClaimedIds transcode = claimOperation();
		String videoUri = "s3://media-output/jobs/" + jobId + "/operations/" + transcode.operationId() + "/video-1080p.mp4";
		mockMvc.perform(post("/internal/operations/" + transcode.operationId() + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content(transcodeCompleteJson(transcode.attemptId(), videoUri, 120)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		mockMvc.perform(get("/jobs/" + jobId + "/artifacts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(3));
	}

	@Test
	void jobStaysNotCompletedWhenUnsupportedOperationsRemain() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/mixed.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "H264_TO_AV1"}
				  ]
				}
				""");
		ClaimedIds claimed = claimOperation();
		completeMetadata(claimed.operationId(), claimed.attemptId());

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
								{"attemptId": "%s", "reason": "missing"}
								""".formatted(UUID.randomUUID())))
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

		MvcResult started = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.attemptId").isString())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.leaseExpiresAt").isString())
				.andReturn();
		UUID attemptId = UUID.fromString(JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId"));
		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ALREADY_RUNNING"));

		Integer attemptCount = jdbcTemplate.queryForObject(
				"select count(*) from execution_attempts where operation_id = ?",
				Integer.class,
				operationId
		);
		assertThat(attemptCount).isEqualTo(1);

		completeMetadata(operationId, attemptId);
		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(jsonPath("$.outcome").value("ALREADY_TERMINAL"));
	}

	@Test
	void startUnknownOperationReturns404() throws Exception {
		mockMvc.perform(post("/internal/operations/" + UUID.randomUUID() + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
	}

	@Test
	void unknownWorkerCannotStart() throws Exception {
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

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("missing-worker")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("WORKER_NOT_FOUND"));
	}

	private UUID createJob(String json) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}

	private ClaimedIds claimOperation() throws Exception {
		MvcResult result = mockMvc.perform(post("/internal/operations/claim")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andReturn();
		String body = result.getResponse().getContentAsString();
		return new ClaimedIds(
				UUID.fromString(JsonPath.read(body, "$.operationId")),
				UUID.fromString(JsonPath.read(body, "$.attemptId"))
		);
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

	private static String thumbnailCompleteJson(UUID attemptId, String objectUri, int sizeBytes) {
		return """
				{
				  "attemptId": "%s",
				  "actualRuntimeMs": 20,
				  "artifact": {
				    "objectUri": "%s",
				    "contentType": "image/jpeg",
				    "sizeBytes": %d,
				    "checksum": "%s"
				  }
				}
				""".formatted(attemptId, objectUri, sizeBytes, SHA256);
	}

	private static String audioCompleteJson(UUID attemptId, String objectUri, int sizeBytes) {
		return """
				{
				  "attemptId": "%s",
				  "actualRuntimeMs": 20,
				  "artifact": {
				    "objectUri": "%s",
				    "contentType": "audio/mp4",
				    "sizeBytes": %d,
				    "checksum": "%s"
				  }
				}
				""".formatted(attemptId, objectUri, sizeBytes, SHA256);
	}

	private static String transcodeCompleteJson(UUID attemptId, String objectUri, int sizeBytes) {
		return """
				{
				  "attemptId": "%s",
				  "actualRuntimeMs": 20,
				  "artifact": {
				    "objectUri": "%s",
				    "contentType": "video/mp4",
				    "sizeBytes": %d,
				    "checksum": "%s"
				  }
				}
				""".formatted(attemptId, objectUri, sizeBytes, SHA256);
	}

	private record ClaimedIds(UUID operationId, UUID attemptId) {
	}
}
