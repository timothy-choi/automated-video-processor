package com.example.drive.job;

import com.example.drive.support.AuthenticatedApiTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.job.dto.StartOperationResponse;
import com.example.drive.job.dto.StartOutcome;
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
class AssignmentRecoveryIntegrationTest extends AuthenticatedApiTest {

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
	void expiredUnstartedAssignmentOnUnavailableWorkerIsRequeued() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID jobId = createJob();
		UUID operationId = operationId(jobId);
		var assigned = schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		expireAssignment(operationId);
		markUnavailable("worker-a");

		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isEqualTo(1);

		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		assertThat(jobStatus(jobId)).isEqualTo("QUEUED");
		assertThat(assignedAt(operationId)).isNull();
		assertThat(assignedWorkerId(operationId)).isNull();
		assertThat(currentAssignmentId(operationId)).isNull();
		assertThat(outboxCount(operationId)).isZero();
		assertThat(attemptCount(operationId)).isZero();
		assertThat(decisionCount(operationId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"select id::text from scheduling_decisions where operation_id = ?",
				String.class,
				operationId
		)).isEqualTo(assigned.decisionId().toString());
	}

	@Test
	void assignmentThatHasNotTimedOutStaysAssigned() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID operationId = operationId(createJob());
		schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		markUnavailable("worker-a");

		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isZero();
		assertThat(operationStatus(operationId)).isEqualTo("ASSIGNED");
		assertThat(outboxCount(operationId)).isEqualTo(1);
	}

	@Test
	void expiredAssignmentOnAvailableWorkerIsNotReclaimed() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID operationId = operationId(createJob());
		schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		expireAssignment(operationId);

		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isZero();
		assertThat(operationStatus(operationId)).isEqualTo("ASSIGNED");
		assertThat(outboxCount(operationId)).isEqualTo(1);
	}

	@Test
	void runningAttemptIsNotTouchedByAssignmentRecovery() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID jobId = createJob();
		UUID operationId = operationId(jobId);
		var assigned = schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		start(operationId, "worker-a", assigned.decisionId());
		expireAssignment(operationId);
		markUnavailable("worker-a");

		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isZero();
		assertThat(operationStatus(operationId)).isEqualTo("RUNNING");
		assertThat(jobStatus(jobId)).isEqualTo("RUNNING");
		assertThat(attemptCount(operationId)).isEqualTo(1);
	}

	@Test
	void recoveredOperationCanBeAssignedToAnotherWorker() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID jobId = createJob();
		UUID operationId = operationId(jobId);
		schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		expireAssignment(operationId);
		markUnavailable("worker-a");
		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isEqualTo(1);

		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations[0].operationId").value(operationId.toString()));

		var reassigned = schedulerService.assign(new AssignOperationRequest(operationId, "worker-b", "FIFO", "LEXICOGRAPHIC"));
		assertThat(reassigned.workerId()).isEqualTo("worker-b");
		assertThat(operationStatus(operationId)).isEqualTo("ASSIGNED");
		assertThat(jobStatus(jobId)).isEqualTo("ASSIGNED");
		assertThat(decisionCount(operationId)).isEqualTo(2);
		assertThat(outboxCount(operationId)).isEqualTo(1);
		assertThat(currentAssignmentId(operationId)).isEqualTo(reassigned.decisionId().toString());
		assertThat(assignedWorkerId(operationId)).isEqualTo("worker-b");
	}

	@Test
	void oldAssignmentCannotStartAfterReassignment() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID operationId = operationId(createJob());
		var first = schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		expireAssignment(operationId);
		markUnavailable("worker-a");
		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isEqualTo(1);
		var second = schedulerService.assign(new AssignOperationRequest(operationId, "worker-b", "FIFO", "LEXICOGRAPHIC"));
		jdbcTemplate.update("update workers set status = 'AVAILABLE' where id = 'worker-a'");

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-a", first.decisionId())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_ASSIGNMENT"));

		assertThat(operationStatus(operationId)).isEqualTo("ASSIGNED");
		assertThat(assignedWorkerId(operationId)).isEqualTo("worker-b");
		assertThat(currentAssignmentId(operationId)).isEqualTo(second.decisionId().toString());
		assertThat(attemptCount(operationId)).isZero();

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-b", second.decisionId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andExpect(jsonPath("$.workerId").value("worker-b"));
		assertThat(operationStatus(operationId)).isEqualTo("RUNNING");
		assertThat(attemptCount(operationId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"select worker_id from execution_attempts where operation_id = ?",
				String.class,
				operationId
		)).isEqualTo("worker-b");
	}

	@Test
	void startWithoutCurrentAssignmentIdIsRejectedForSchedulerPlacement() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID operationId = operationId(createJob());
		schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_ASSIGNMENT"));
		assertThat(attemptCount(operationId)).isZero();
		assertThat(operationStatus(operationId)).isEqualTo("ASSIGNED");
	}

	@Test
	void concurrentStartAndAssignmentRecoveryDoNotDuplicateOwnership() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID operationId = operationId(createJob());
		var assigned = schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		expireAssignment(operationId);

		AtomicReference<StartOperationResponse> startResult = new AtomicReference<>();
		AtomicReference<Throwable> startError = new AtomicReference<>();
		AtomicInteger reclaimed = new AtomicInteger();
		CountDownLatch startGate = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> startFuture = pool.submit(() -> {
				await(startGate);
				try {
					startResult.set(internalOperationService.start(
							operationId,
							"worker-a",
							assigned.decisionId()
					));
				}
				catch (RuntimeException ex) {
					startError.set(ex);
				}
			});
			Future<?> recoverFuture = pool.submit(() -> {
				await(startGate);
				jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-a'");
				reclaimed.set(assignmentRecoveryService.reclaimUnstartedAssignments());
			});
			startGate.countDown();
			startFuture.get(10, TimeUnit.SECONDS);
			recoverFuture.get(10, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}

		int attempts = attemptCount(operationId);
		String status = operationStatus(operationId);
		assertThat(attempts).isLessThanOrEqualTo(1);
		if (StartOutcome.STARTED.equals(startResult.get() == null ? null : startResult.get().outcome())) {
			assertThat(status).isEqualTo("RUNNING");
			assertThat(attempts).isEqualTo(1);
			assertThat(reclaimed.get()).isZero();
		}
		else {
			assertThat(status).isEqualTo("QUEUED");
			assertThat(attempts).isZero();
			assertThat(startResult.get() == null || startResult.get().outcome() == StartOutcome.INVALID_STATE
					|| startError.get() instanceof WorkerNotEligibleException).isTrue();
		}
	}

	@Test
	void assignmentRecoveryLeavesLeaseRecoveryUnchanged() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID operationId = operationId(createJob());
		var assigned = schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		MvcResult started = start(operationId, "worker-a", assigned.decisionId());
		UUID attemptId = UUID.fromString(JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId"));

		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isZero();
		assertThat(operationStatus(operationId)).isEqualTo("RUNNING");

		jdbcTemplate.update(
				"update execution_attempts set lease_expires_at = ? where id = ?",
				Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
				attemptId
		);
		markUnavailable("worker-a");
		assertThat(internalOperationService.reclaimExpiredAttempts()).isEqualTo(1);
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		assertThat(jdbcTemplate.queryForObject(
				"select status from execution_attempts where id = ?",
				String.class,
				attemptId
		)).isEqualTo("INTERRUPTED");
	}

	private UUID createJob() throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://media-input/video.mp4",
								  "operations": [{"type": "METADATA"}]
								}
								"""))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}

	private MvcResult start(UUID operationId, String workerId, UUID assignmentId) throws Exception {
		return mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson(workerId, assignmentId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andReturn();
	}

	private void expireAssignment(UUID operationId) {
		jdbcTemplate.update(
				"update operations set assigned_at = ? where id = ?",
				Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
				operationId
		);
	}

	private void markUnavailable(String workerId) {
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = ?", workerId);
	}

	private UUID operationId(UUID jobId) {
		return UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ? order by operation_order asc limit 1",
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

	private Timestamp assignedAt(UUID operationId) {
		return jdbcTemplate.queryForObject("select assigned_at from operations where id = ?", Timestamp.class, operationId);
	}

	private String assignedWorkerId(UUID operationId) {
		return jdbcTemplate.queryForObject(
				"select assigned_worker_id from operations where id = ?",
				String.class,
				operationId
		);
	}

	private String currentAssignmentId(UUID operationId) {
		return jdbcTemplate.queryForObject(
				"select current_assignment_id::text from operations where id = ?",
				String.class,
				operationId
		);
	}

	private Integer outboxCount(UUID operationId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from dispatch_outbox where operation_id = ?",
				Integer.class,
				operationId
		);
	}

	private Integer decisionCount(UUID operationId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from scheduling_decisions where operation_id = ?",
				Integer.class,
				operationId
		);
	}

	private int attemptCount(UUID operationId) {
		Integer count = jdbcTemplate.queryForObject(
				"select count(*) from execution_attempts where operation_id = ?",
				Integer.class,
				operationId
		);
		return count == null ? 0 : count;
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting to start concurrent recovery");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}
}
