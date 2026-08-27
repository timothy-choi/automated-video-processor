package com.example.drive.scheduler;

import com.example.drive.support.AuthenticatedApiTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
class RoundRobinIntegrationTest extends AuthenticatedApiTest {

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
	void roundRobinRotatesEligibleWorkers() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-c");
		List<UUID> operations = List.of(queuedMetadata(), queuedMetadata(), queuedMetadata(), queuedMetadata(), queuedMetadata(), queuedMetadata());
		List<String> workers = new ArrayList<>();
		for (UUID operationId : operations) {
			workers.add(assignRr(operationId).workerId());
		}
		assertThat(workers).containsExactly("worker-a", "worker-b", "worker-c", "worker-a", "worker-b", "worker-c");
		assertThat(jdbcTemplate.queryForList(
				"select worker_id from scheduling_decisions where worker_policy = 'ROUND_ROBIN' order by created_at, id",
				String.class
		)).containsExactly("worker-a", "worker-b", "worker-c", "worker-a", "worker-b", "worker-c");
	}

	@Test
	void thumbnailRoundRobinSkipsMetadataOnlyWorker() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b", "METADATA");
		WorkerTestSupport.register(mockMvc, "worker-c");
		List<String> workers = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			workers.add(assignRr(queuedThumbnail()).workerId());
		}
		assertThat(workers).containsExactly("worker-a", "worker-c", "worker-a", "worker-c");
	}

	@Test
	void unavailableWorkerIsSkippedAndCanRejoin() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-c");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-a");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-b");
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-b'");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-c");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-a");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-c");
		jdbcTemplate.update("update workers set status = 'AVAILABLE' where id = 'worker-b'");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-a");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-b");
	}

	@Test
	void restartUsesDurableRoundRobinHistory() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-c");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-a");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-b");
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roundRobinCursors.METADATA").value("worker-b"));
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-c");
	}

	@Test
	void concurrentRoundRobinDoesNotRepeatTheSameWorker() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-c");
		UUID firstOp = queuedMetadata();
		UUID secondOp = queuedMetadata();
		AtomicReference<String> firstWorker = new AtomicReference<>();
		AtomicReference<String> secondWorker = new AtomicReference<>();
		AtomicReference<Throwable> firstError = new AtomicReference<>();
		AtomicReference<Throwable> secondError = new AtomicReference<>();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> a = pool.submit(() -> {
				await(start);
				try {
					firstWorker.set(assignRr(firstOp).workerId());
				}
				catch (RuntimeException ex) {
					firstError.set(ex);
				}
			});
			Future<?> b = pool.submit(() -> {
				await(start);
				try {
					secondWorker.set(assignRr(secondOp).workerId());
				}
				catch (RuntimeException ex) {
					secondError.set(ex);
				}
			});
			start.countDown();
			a.get(10, TimeUnit.SECONDS);
			b.get(10, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
		if (firstWorker.get() == null) {
			assertThat(firstError.get()).isNotNull();
			firstWorker.set(assignRr(firstOp).workerId());
		}
		if (secondWorker.get() == null) {
			assertThat(secondError.get()).isNotNull();
			secondWorker.set(assignRr(secondOp).workerId());
		}
		assertThat(List.of(firstWorker.get(), secondWorker.get())).containsExactlyInAnyOrder("worker-a", "worker-b");
	}

	@Test
	void rejectedRoundRobinProposalDoesNotAdvanceCursor() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-c");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-a");
		UUID next = queuedMetadata();
		assertThatThrownBy(() -> schedulerService.assign(new AssignOperationRequest(
				next,
				"worker-a",
				"FIFO",
				"ROUND_ROBIN"
		))).isInstanceOf(com.example.drive.job.WorkerNotEligibleException.class);
		assertThat(latestRrWorker()).isEqualTo("worker-a");
		assertThat(operationStatus(next)).isEqualTo("QUEUED");
		assertThat(assignRr(next).workerId()).isEqualTo("worker-b");
	}

	@Test
	void unavailableWorkerProposalDoesNotAdvanceCursor() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-c");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-a");
		UUID next = queuedMetadata();
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-b'");
		assertThatThrownBy(() -> schedulerService.assign(new AssignOperationRequest(
				next,
				"worker-b",
				"FIFO",
				"ROUND_ROBIN"
		))).isInstanceOf(com.example.drive.job.WorkerNotEligibleException.class);
		assertThat(latestRrWorker()).isEqualTo("worker-a");
		assertThat(operationStatus(next)).isEqualTo("QUEUED");
		assertThat(assignRr(next).workerId()).isEqualTo("worker-c");
	}

	@Test
	void noEligibleWorkerLeavesWorkQueued() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA");
		UUID operationId = queuedThumbnail();
		assertThat(jdbcTemplate.queryForList(
				"""
						select w.id from workers w
						join worker_supported_operations s on s.worker_id = w.id
						where w.status = 'AVAILABLE' and s.operation_type = 'THUMBNAIL'
						""",
				String.class
		)).isEmpty();
		assertThatThrownBy(() -> schedulerService.assign(new AssignOperationRequest(
				operationId,
				"worker-a",
				"FIFO",
				"ROUND_ROBIN"
		))).isInstanceOf(com.example.drive.job.WorkerNotEligibleException.class);
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		assertThat(jdbcTemplate.queryForObject("select count(*) from scheduling_decisions", Integer.class)).isZero();
	}

	@Test
	void metadataAndThumbnailRoundRobinIndependently() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b", "METADATA");
		WorkerTestSupport.register(mockMvc, "worker-c");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-a");
		assertThat(assignRr(queuedThumbnail()).workerId()).isEqualTo("worker-a");
		assertThat(assignRr(queuedMetadata()).workerId()).isEqualTo("worker-b");
		assertThat(assignRr(queuedThumbnail()).workerId()).isEqualTo("worker-c");
	}

	@Test
	void roundRobinDoesNotChangeFifoOperationOrder() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID later = queuedMetadata();
		UUID earlier = queuedMetadata();
		jdbcTemplate.update(
				"update operations set created_at = ?, queued_at = ? where id = ?",
				Timestamp.from(Instant.parse("2026-08-25T10:01:00Z")),
				Timestamp.from(Instant.parse("2026-08-25T10:01:00Z")),
				later
		);
		jdbcTemplate.update(
				"update operations set created_at = ?, queued_at = ? where id = ?",
				Timestamp.from(Instant.parse("2026-08-25T10:00:00Z")),
				Timestamp.from(Instant.parse("2026-08-25T10:00:00Z")),
				earlier
		);
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations[0].operationId").value(earlier.toString()))
				.andExpect(jsonPath("$.operations[1].operationId").value(later.toString()));
		assertThat(assignRr(earlier).workerId()).isEqualTo("worker-a");
		assertThat(assignRr(later).workerId()).isEqualTo("worker-b");
	}

	@Test
	void recoveredUnstartedAssignmentIsPlacedByRoundRobin() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID operationId = queuedMetadata();
		var first = assignRr(operationId);
		assertThat(first.workerId()).isEqualTo("worker-a");
		jdbcTemplate.update(
				"update operations set assigned_at = ? where id = ?",
				Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
				operationId
		);
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-a'");
		assertThat(assignmentRecoveryService.reclaimUnstartedAssignments()).isEqualTo(1);
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		var second = assignRr(operationId);
		assertThat(second.workerId()).isEqualTo("worker-b");
		assertThat(second.decisionId()).isNotEqualTo(first.decisionId());
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from scheduling_decisions where operation_id = ?",
				Integer.class,
				operationId
		)).isEqualTo(2);
	}

	@Test
	void recoveredInterruptedAttemptIsPlacedByRoundRobin() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID operationId = queuedMetadata();
		var first = assignRr(operationId);
		assertThat(first.workerId()).isEqualTo("worker-a");
		MvcResult started = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-a", first.decisionId())))
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andReturn();
		UUID attemptId = UUID.fromString(JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId"));
		jdbcTemplate.update(
				"update execution_attempts set lease_expires_at = ? where id = ?",
				Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
				attemptId
		);
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-a'");
		assertThat(internalOperationService.reclaimExpiredAttempts()).isEqualTo(1);
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		var second = assignRr(operationId);
		assertThat(second.workerId()).isEqualTo("worker-b");
		assertThat(second.decisionId()).isNotEqualTo(first.decisionId());
	}

	private com.example.drive.scheduler.dto.AssignOperationResponse assignRr(UUID operationId) {
		return schedulerService.assign(new AssignOperationRequest(operationId, nextRrWorker(operationId), "FIFO", "ROUND_ROBIN"));
	}

	private String latestRrWorker() {
		return jdbcTemplate.queryForObject(
				"select worker_id from scheduling_decisions where worker_policy = 'ROUND_ROBIN' order by created_at desc, id desc limit 1",
				String.class
		);
	}

	private String nextRrWorker(UUID operationId) {
		String type = jdbcTemplate.queryForObject(
				"select operation_type from operations where id = ?",
				String.class,
				operationId
		);
		List<String> eligible = jdbcTemplate.queryForList(
				"""
						select w.id from workers w
						join worker_supported_operations s on s.worker_id = w.id
						where w.status = 'AVAILABLE' and s.operation_type = ?
						order by w.id
						""",
				String.class,
				type
		);
		String last = jdbcTemplate.query(
				"""
						select sd.worker_id from scheduling_decisions sd
						join operations o on o.id = sd.operation_id
						where sd.worker_policy = 'ROUND_ROBIN' and o.operation_type = ?
						order by sd.created_at desc, sd.id desc
						limit 1
						""",
				rs -> rs.next() ? rs.getString(1) : null,
				type
		);
		return WorkerPlacement.next(WorkerPlacement.ROUND_ROBIN, eligible, last);
	}

	private UUID queuedMetadata() throws Exception {
		return queued("METADATA");
	}

	private UUID queuedThumbnail() throws Exception {
		return queued("THUMBNAIL");
	}

	private UUID queued(String type) throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/jobs"))
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

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting to start concurrent round robin");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}
}
