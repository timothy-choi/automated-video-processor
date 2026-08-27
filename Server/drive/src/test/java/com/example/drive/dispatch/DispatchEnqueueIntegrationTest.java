package com.example.drive.dispatch;

import com.example.drive.support.AuthenticatedApiTest;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.support.DispatchServiceTest;
import com.example.drive.support.WorkerTestSupport;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DispatchServiceTest
class DispatchEnqueueIntegrationTest extends AuthenticatedApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DispatchEnqueueService enqueueService;

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
		WorkerTestSupport.register(mockMvc, "worker-a");
	}

	@Test
	void postJobsDoesNotCreateOutboxRows() throws Exception {
		createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");

		Integer outbox = jdbcTemplate.queryForObject("select count(*) from dispatch_outbox", Integer.class);
		assertThat(outbox).isZero();
		Integer queued = jdbcTemplate.queryForObject(
				"select count(*) from operations where status = 'QUEUED'",
				Integer.class
		);
		assertThat(queued).isEqualTo(1);
	}

	@Test
	void enqueueAssignsSupportedOperationsAndWritesPendingOutbox() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "THUMBNAIL"}
				  ]
				}
				""");

		assertThat(enqueueService.enqueueDispatchableOperations()).isEqualTo(2);

		mockMvc.perform(authed(get("/jobs/" + jobId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ASSIGNED"))
				.andExpect(jsonPath("$.operations[0].status").value("ASSIGNED"))
				.andExpect(jsonPath("$.operations[1].status").value("ASSIGNED"));

		Integer pending = jdbcTemplate.queryForObject(
				"select count(*) from dispatch_outbox where status = 'PENDING'",
				Integer.class
		);
		assertThat(pending).isEqualTo(2);

		String payload = jdbcTemplate.queryForObject(
				"select payload_json::text from dispatch_outbox order by created_at, operation_id limit 1",
				String.class
		);
		assertThat(payload).contains("schemaVersion").contains("s3://media-input/video.mp4");
		assertThat(payload).contains("operationId").contains("jobId").contains("dispatchedAt");
		assertThat(payload).doesNotContain("hibernate");
	}

	@Test
	void enqueueIsIdempotentOnceAssigned() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/sample.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		assertThat(enqueueService.enqueueDispatchableOperations()).isEqualTo(1);
		assertThat(enqueueService.enqueueDispatchableOperations()).isZero();
		Integer outbox = jdbcTemplate.queryForObject("select count(*) from dispatch_outbox", Integer.class);
		assertThat(outbox).isEqualTo(1);
	}

	@Test
	void enqueueIncludesH264ToAv1() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "file:///tmp/mixed.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "H264_TO_AV1"}
				  ]
				}
				""");

		assertThat(enqueueService.enqueueDispatchableOperations()).isEqualTo(2);

		mockMvc.perform(authed(get("/jobs/" + jobId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ASSIGNED"))
				.andExpect(jsonPath("$.operations[0].status").value("ASSIGNED"))
				.andExpect(jsonPath("$.operations[1].status").value("ASSIGNED"));

		Integer av1Outbox = jdbcTemplate.queryForObject(
				"""
						select count(*) from dispatch_outbox o
						join operations op on op.id = o.operation_id
						where op.operation_type = 'H264_TO_AV1'
						""",
				Integer.class
		);
		assertThat(av1Outbox).isEqualTo(1);
	}

	@Test
	void startMovesAssignedToRunningAndRejectsDuplicates() throws Exception {
		createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations",
				String.class
		));

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.attemptId").isString())
				.andExpect(jsonPath("$.inputUri").value("s3://media-input/video.mp4"));

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ALREADY_RUNNING"))
				.andExpect(jsonPath("$.status").value("RUNNING"));

		Integer running = jdbcTemplate.queryForObject(
				"select count(*) from operations where id = ? and status = 'RUNNING' and started_at is not null",
				Integer.class,
				operationId
		);
		assertThat(running).isEqualTo(1);
	}

	@Test
	void startOnQueuedIsInvalidStateAndDoesNotRun() throws Exception {
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

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("INVALID_STATE"))
				.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	void completionAndFailureStillWorkAfterStart() throws Exception {
		createJob("""
				{
				  "inputUri": "file:///tmp/complete.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = UUID.fromString(jdbcTemplate.queryForObject("select id from operations", String.class));
		MvcResult started = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andReturn();
		String attemptId = JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId");

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "attemptId": "%s",
								  "actualRuntimeMs": 9,
								  "metadata": {"formatName": "mp4", "width": 320}
								}
								""".formatted(attemptId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.result.width").value(320));

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ALREADY_TERMINAL"));
	}

	@Test
	void failedStartPathStillPersistsFailure() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/missing.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = UUID.fromString(jdbcTemplate.queryForObject("select id from operations", String.class));
		MvcResult started = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andReturn();
		String attemptId = JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId");

		mockMvc.perform(post("/internal/operations/" + operationId + "/fail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"attemptId": "%s", "reason": "object not found"}
								""".formatted(attemptId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));

		mockMvc.perform(authed(get("/jobs/" + jobId)))
				.andExpect(jsonPath("$.status").value("FAILED"));
		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(jsonPath("$.outcome").value("ALREADY_TERMINAL"));
	}

	@Test
	void thumbnailArtifactUnchangedAfterDispatchStart() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");
		enqueueService.enqueueDispatchableOperations();
		UUID operationId = UUID.fromString(jdbcTemplate.queryForObject("select id from operations", String.class));
		MvcResult started = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andReturn();
		String attemptId = JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId");
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg";
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "attemptId": "%s",
								  "actualRuntimeMs": 20,
								  "artifact": {
								    "objectUri": "%s",
								    "contentType": "image/jpeg",
								    "sizeBytes": 99,
								    "checksum": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
								  }
								}
								""".formatted(attemptId, objectUri)))
				.andExpect(status().isOk());

		mockMvc.perform(authed(get("/jobs/" + jobId + "/artifacts")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(1))
				.andExpect(jsonPath("$.artifacts[0].objectUri").value(objectUri));
	}

	@Test
	void claimEndpointIsDisabledWhenFlagOff() throws Exception {
		mockMvc.perform(post("/internal/operations/claim")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLAIM_DISABLED"));
	}

	private UUID createJob(String json) throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}
}
