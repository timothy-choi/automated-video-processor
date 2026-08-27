package com.example.drive.scheduler;

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

import com.example.drive.job.AssignmentRecoveryService;
import com.example.drive.job.InternalOperationService;
import com.example.drive.scheduler.dto.AssignOperationRequest;
import com.example.drive.support.DispatchServiceTest;
import com.example.drive.support.WorkerTestSupport;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DispatchServiceTest
class LeastLoadedIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SchedulerService schedulerService;

	@Autowired
	private AssignmentRecoveryService assignmentRecoveryService;

	@Autowired
	private InternalOperationService internalOperationService;

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
	}

	@Test
	void snapshotExposesRunningAttemptCounts() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		startRunning("worker-a");
		startRunning("worker-a");
		startRunning("worker-b");
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workers[0].id").value("worker-a"))
				.andExpect(jsonPath("$.workers[0].activeOperations").value(2))
				.andExpect(jsonPath("$.workers[1].id").value("worker-b"))
				.andExpect(jsonPath("$.workers[1].activeOperations").value(1));
	}

	@Test
	void leastLoadedChoosesIdleWorker() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-c");
		startRunning("worker-a");
		startRunning("worker-a");
		startRunning("worker-c");
		UUID next = queuedMetadata();
		var assigned = assignLl(next, "worker-b");
		assertThat(assigned.workerId()).isEqualTo("worker-b");
		assertThat(assigned.operationPolicy()).isEqualTo("FIFO");
		assertThat(assigned.workerPolicy()).isEqualTo("LEAST_LOADED");
		assertThat(jdbcTemplate.queryForObject(
				"select worker_policy from scheduling_decisions where operation_id = ?",
				String.class,
				next
		)).isEqualTo("LEAST_LOADED");
	}

	@Test
	void leastLoadedTieBreaksByWorkerId() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-c");
		startRunning("worker-a");
		startRunning("worker-b");
		startRunning("worker-c");
		startRunning("worker-c");
		UUID next = queuedMetadata();
		assertThat(assignLl(next, "worker-a").workerId()).isEqualTo("worker-a");
	}

	@Test
	void thumbnailIgnoresMetadataOnlyEvenWhenIdle() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b", "METADATA");
		WorkerTestSupport.register(mockMvc, "worker-c");
		startRunning("worker-a");
		startRunning("worker-a");
		UUID next = queuedThumbnail();
		assertThat(assignLl(next, "worker-c").workerId()).isEqualTo("worker-c");
	}

	@Test
	void unavailableIdleWorkerIsNotSelected() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		startRunning("worker-b");
		startRunning("worker-b");
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-a'");
		UUID next = queuedMetadata();
		assertThatThrownBy(() -> assignLl(next, "worker-a"))
				.isInstanceOf(com.example.drive.job.WorkerNotEligibleException.class);
		assertThat(assignLl(next, "worker-b").workerId()).isEqualTo("worker-b");
	}

	@Test
	void assignedButNotStartedDoesNotCountAsLoad() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID parked = queuedMetadata();
		schedulerService.assign(new AssignOperationRequest(parked, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		assertThat(operationStatus(parked)).isEqualTo("ASSIGNED");
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workers[0].id").value("worker-a"))
				.andExpect(jsonPath("$.workers[0].activeOperations").value(0))
				.andExpect(jsonPath("$.workers[1].activeOperations").value(0));
	}

	@Test
	void startIncreasesLoadAndCompleteDecreasesIt() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(jsonPath("$.workers[0].activeOperations").value(0));
		Started running = startRunning("worker-a");
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(jsonPath("$.workers[0].id").value("worker-a"))
				.andExpect(jsonPath("$.workers[0].activeOperations").value(1))
				.andExpect(jsonPath("$.workers[1].activeOperations").value(0));
		completeMetadata(running.operationId(), running.attemptId());
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(jsonPath("$.workers[0].activeOperations").value(0))
				.andExpect(jsonPath("$.workers[1].activeOperations").value(0));
	}

	@Test
	void leastLoadedCommitDoesNotRequireOptimality() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID operationId = queuedMetadata();
		assertThat(assignLl(operationId, "worker-b").workerId()).isEqualTo("worker-b");
	}

	@Test
	void noEligibleWorkerLeavesWorkQueued() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA");
		UUID operationId = queuedThumbnail();
		assertThatThrownBy(() -> assignLl(operationId, "worker-a"))
				.isInstanceOf(com.example.drive.job.WorkerNotEligibleException.class);
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		assertThat(jdbcTemplate.queryForObject("select count(*) from scheduling_decisions", Integer.class)).isZero();
	}

	@Test
	void leastLoadedDoesNotChangeFifoOperationOrder() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID later = queuedMetadata();
		UUID earlier = queuedMetadata();
		jdbcTemplate.update(
				"update operations set created_at = ?, queued_at = ? where id = ?",
				Timestamp.from(Instant.parse("2026-08-26T10:01:00Z")),
				Timestamp.from(Instant.parse("2026-08-26T10:01:00Z")),
				later
		);
		jdbcTemplate.update(
				"update operations set created_at = ?, queued_at = ? where id = ?",
				Timestamp.from(Instant.parse("2026-08-26T10:00:00Z")),
				Timestamp.from(Instant.parse("2026-08-26T10:00:00Z")),
				earlier
		);
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations[0].operationId").value(earlier.toString()))
				.andExpect(jsonPath("$.operations[1].operationId").value(later.toString()));
	}

	@Test
	void recoveredUnstartedAssignmentIsPlacedByLeastLoaded() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID operationId = queuedMetadata();
		var first = schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEAST_LOADED"));
		jdbcTemplate.update(
				"update operations set assigned_at = ? where id = ?",
				Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
				operationId
		);
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-a'");
		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isEqualTo(1);
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		var second = assignLl(operationId, "worker-b");
		assertThat(second.workerId()).isEqualTo("worker-b");
		assertThat(second.decisionId()).isNotEqualTo(first.decisionId());
		assertThat(second.workerPolicy()).isEqualTo("LEAST_LOADED");
	}

	@Test
	void recoveredInterruptedAttemptIsPlacedByLeastLoaded() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		Started first = startRunning("worker-a");
		jdbcTemplate.update(
				"update execution_attempts set lease_expires_at = ? where id = ?",
				Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
				first.attemptId()
		);
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-a'");
		assertThat(internalOperationService.reclaimExpiredAttempts()).isEqualTo(1);
		assertThat(operationStatus(first.operationId())).isEqualTo("QUEUED");
		var second = assignLl(first.operationId(), "worker-b");
		assertThat(second.workerId()).isEqualTo("worker-b");
		assertThat(second.workerPolicy()).isEqualTo("LEAST_LOADED");
	}

	private com.example.drive.scheduler.dto.AssignOperationResponse assignLl(UUID operationId, String workerId) {
		return schedulerService.assign(new AssignOperationRequest(operationId, workerId, "FIFO", "LEAST_LOADED"));
	}

	private Started startRunning(String workerId) throws Exception {
		UUID operationId = queuedMetadata();
		var assigned = schedulerService.assign(new AssignOperationRequest(operationId, workerId, "FIFO", "LEAST_LOADED"));
		MvcResult started = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson(workerId, assigned.decisionId())))
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andReturn();
		UUID attemptId = UUID.fromString(JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId"));
		return new Started(operationId, attemptId);
	}

	private void completeMetadata(UUID operationId, UUID attemptId) throws Exception {
		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "attemptId": "%s",
								  "actualRuntimeMs": 12,
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
								""".formatted(attemptId)))
				.andExpect(status().isOk());
	}

	private UUID queuedMetadata() throws Exception {
		return queued("METADATA");
	}

	private UUID queuedThumbnail() throws Exception {
		return queued("THUMBNAIL");
	}

	private UUID queued(String type) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"%s"}]}
								""".formatted(type)))
				.andExpect(status().isAccepted())
				.andReturn();
		UUID jobId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
		return UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ? order by operation_order asc limit 1",
				String.class,
				jobId
		));
	}

	private String operationStatus(UUID operationId) {
		return jdbcTemplate.queryForObject("select status from operations where id = ?", String.class, operationId);
	}

	private record Started(UUID operationId, UUID attemptId) {
	}
}
