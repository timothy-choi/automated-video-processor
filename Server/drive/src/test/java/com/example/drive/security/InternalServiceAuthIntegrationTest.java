package com.example.drive.security;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.support.AuthenticatedApiTest;
import com.example.drive.support.ControlServiceTest;
import com.example.drive.support.InternalAuthSupport;
import com.example.drive.support.WorkerTestSupport;
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
class InternalServiceAuthIntegrationTest extends AuthenticatedApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearWorkers() throws Exception {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
		jdbcTemplate.update("delete from scheduling_decisions");
		jdbcTemplate.update("delete from dispatch_outbox");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
		jdbcTemplate.execute("delete from worker_supported_codecs");
		jdbcTemplate.execute("delete from worker_supported_operations");
		jdbcTemplate.execute("delete from workers");
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
	}

	@Test
	void schedulerSnapshotRequiresSchedulerToken() throws Exception {
		mockMvc.perform(InternalAuthSupport.unauthenticated(get("/internal/scheduler/snapshot")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.message").value("Authentication required"));

		mockMvc.perform(get("/internal/scheduler/snapshot")
						.header("Authorization", "Bearer wrong-scheduler-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		mockMvc.perform(InternalAuthSupport.worker(get("/internal/scheduler/snapshot"), "worker-a"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.message").value("Forbidden"));

		mockMvc.perform(authed(get("/internal/scheduler/snapshot")))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(InternalAuthSupport.scheduler(get("/internal/scheduler/snapshot")))
				.andExpect(status().isOk());
	}

	@Test
	void schedulerAssignRequiresSchedulerToken() throws Exception {
		UUID operationId = queuedOperation();
		String body = assignJson(operationId, "worker-a");

		mockMvc.perform(InternalAuthSupport.unauthenticated(post("/internal/scheduler/assign"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(InternalAuthSupport.worker(post("/internal/scheduler/assign"), "worker-a")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		mockMvc.perform(InternalAuthSupport.scheduler(post("/internal/scheduler/assign"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-a"));
	}

	@Test
	void workerRegisterRequiresMatchingWorkerToken() throws Exception {
		mockMvc.perform(InternalAuthSupport.unauthenticated(post("/internal/workers/register"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.registrationJson("worker-c")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		mockMvc.perform(authed(post("/internal/workers/register"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.registrationJson("worker-c")))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(InternalAuthSupport.scheduler(post("/internal/workers/register"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.registrationJson("worker-c")))
				.andExpect(status().isForbidden());

		mockMvc.perform(InternalAuthSupport.worker(post("/internal/workers/register"), "worker-a")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.registrationJson("worker-c")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.message").value("Forbidden"));
	}

	@Test
	void workerHeartbeatIsBoundToTokenSubject() throws Exception {
		mockMvc.perform(InternalAuthSupport.unauthenticated(post("/internal/workers/worker-a/heartbeat")))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(InternalAuthSupport.worker(post("/internal/workers/worker-a/heartbeat"), "worker-a"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-a"));

		mockMvc.perform(InternalAuthSupport.worker(post("/internal/workers/worker-b/heartbeat"), "worker-a"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void workerCannotStartAnotherWorkersAssignment() throws Exception {
		UUID operationId = queuedOperation();
		MvcResult assigned = mockMvc.perform(InternalAuthSupport.scheduler(post("/internal/scheduler/assign"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a")))
				.andExpect(status().isOk())
				.andReturn();
		String assignmentId = JsonPath.read(assigned.getResponse().getContentAsString(), "$.decisionId");

		mockMvc.perform(InternalAuthSupport.worker(post("/internal/operations/" + operationId + "/start"), "worker-b")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-a", UUID.fromString(assignmentId))))
				.andExpect(status().isForbidden());

		mockMvc.perform(InternalAuthSupport.scheduler(post("/internal/operations/" + operationId + "/start"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-a", UUID.fromString(assignmentId))))
				.andExpect(status().isForbidden());

		mockMvc.perform(InternalAuthSupport.worker(post("/internal/operations/" + operationId + "/start"), "worker-a")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-a", UUID.fromString(assignmentId))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"));
	}

	@Test
	void workerCannotRenewCompleteFailOrCancelAnotherWorkersAttempt() throws Exception {
		Started started = startAs("worker-b");

		mockMvc.perform(InternalAuthSupport.worker(
						post("/internal/operations/" + started.operationId
								+ "/attempts/" + started.attemptId + "/renew"),
						"worker-a"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-b")))
				.andExpect(status().isForbidden());

		mockMvc.perform(InternalAuthSupport.worker(
						post("/internal/operations/" + started.operationId + "/complete"),
						"worker-a"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content(completeJson(started.attemptId)))
				.andExpect(status().isForbidden());

		mockMvc.perform(InternalAuthSupport.worker(
						post("/internal/operations/" + started.operationId + "/fail"),
						"worker-a"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"attemptId": "%s", "reason": "nope"}
								""".formatted(started.attemptId)))
				.andExpect(status().isForbidden());

		mockMvc.perform(authed(post("/jobs/" + started.jobId + "/cancel")))
				.andExpect(status().isOk());

		mockMvc.perform(InternalAuthSupport.worker(
						post("/internal/operations/" + started.operationId
								+ "/attempts/" + started.attemptId + "/cancelled"),
						"worker-a"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"workerId": "worker-b", "actualRuntimeMs": 3}
								"""))
				.andExpect(status().isForbidden());

		mockMvc.perform(InternalAuthSupport.worker(
						post("/internal/operations/" + started.operationId
								+ "/attempts/" + started.attemptId + "/cancelled"),
						"worker-b"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"workerId": "worker-b", "actualRuntimeMs": 3}
								"""))
				.andExpect(status().isOk());
	}

	@Test
	void workerCanRenewAndCompleteOwnAttempt() throws Exception {
		Started started = startAs("worker-a");
		mockMvc.perform(InternalAuthSupport.worker(
						post("/internal/operations/" + started.operationId
								+ "/attempts/" + started.attemptId + "/renew"),
						"worker-a"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk());

		mockMvc.perform(InternalAuthSupport.worker(
						post("/internal/operations/" + started.operationId + "/complete"),
						"worker-a"
				)
						.contentType(MediaType.APPLICATION_JSON)
						.content(completeJson(started.attemptId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void internalTokenCannotAccessPublicJobsApi() throws Exception {
		mockMvc.perform(InternalAuthSupport.worker(get("/jobs"), "worker-a"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		mockMvc.perform(InternalAuthSupport.scheduler(get("/jobs")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void healthRemainsPublic() throws Exception {
		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void accountRegistrationEnabledAllowsCreate() throws Exception {
		mockMvc.perform(post("/accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"phase-5f-studio\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.apiKey.key").isString());
	}

	@Test
	void claimRequiresWorkerTokenWhenEnabled() throws Exception {
		queuedOperation();
		mockMvc.perform(InternalAuthSupport.unauthenticated(post("/internal/operations/claim"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(InternalAuthSupport.scheduler(post("/internal/operations/claim"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isForbidden());

		mockMvc.perform(InternalAuthSupport.worker(post("/internal/operations/claim"), "worker-a")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk());
	}

	private UUID queuedOperation() throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "file:///tmp/auth.mp4",
								  "operations": [{"type": "METADATA"}]
								}
								"""))
				.andExpect(status().isAccepted())
				.andReturn();
		UUID jobId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
		return UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ?",
				String.class,
				jobId
		));
	}

	private Started startAs(String workerId) throws Exception {
		queuedOperation();
		MvcResult started = mockMvc.perform(InternalAuthSupport.worker(post("/internal/operations/claim"), workerId)
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson(workerId)))
				.andExpect(status().isOk())
				.andReturn();
		String body = started.getResponse().getContentAsString();
		return new Started(
				UUID.fromString(JsonPath.read(body, "$.jobId")),
				UUID.fromString(JsonPath.read(body, "$.operationId")),
				UUID.fromString(JsonPath.read(body, "$.attemptId"))
		);
	}

	private static String assignJson(UUID operationId, String workerId) {
		return """
				{"operationId":"%s","workerId":"%s","operationPolicy":"FIFO","workerPolicy":"LEXICOGRAPHIC"}
				""".formatted(operationId, workerId);
	}

	private static String completeJson(UUID attemptId) {
		return """
				{"attemptId":"%s","actualRuntimeMs":8,"metadata":{"formatName":"mp4"}}
				""".formatted(attemptId);
	}

	private record Started(UUID jobId, UUID operationId, UUID attemptId) {
	}
}
