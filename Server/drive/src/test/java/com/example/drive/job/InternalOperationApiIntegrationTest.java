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

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private InternalOperationService internalOperationService;

	@BeforeEach
	void clearTables() {
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
	void claimReturns204WhenNoWork() throws Exception {
		mockMvc.perform(post("/internal/operations/claim"))
				.andExpect(status().isNoContent());
	}

	@Test
	void claimIgnoresUnsupportedQueuedOperations() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/thumb.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");

		mockMvc.perform(post("/internal/operations/claim"))
				.andExpect(status().isNoContent());
	}

	@Test
	void claimIgnoresMetadataWithNonFileUri() throws Exception {
		createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
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
					return internalOperationService.claimNextMetadataOperation();
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
								  "result": {
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
								  "result": {"formatName": "mp4"}
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
				  "result": {"formatName": "mp4", "width": 320}
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
								  "result": {"formatName": "mp4"}
								}
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"));
	}

	@Test
	void jobStaysNotCompletedWhenUnsupportedOperationsRemain() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/mixed.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "THUMBNAIL"}
				  ]
				}
				""");
		UUID operationId = claimOperationId();

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "actualRuntimeMs": 8,
								  "result": {"formatName": "mp4"}
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

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
}
