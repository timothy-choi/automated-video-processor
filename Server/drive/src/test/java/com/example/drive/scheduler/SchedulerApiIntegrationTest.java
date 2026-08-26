package com.example.drive.scheduler;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.job.InternalOperationService;
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
class SchedulerApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SchedulerService schedulerService;

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
	void snapshotReturnsQueuedSupportedOperationsInFifoOrder() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID laterJob = createJob("""
				{
				  "inputUri": "s3://media-input/later.mp4",
				  "operations": [{"type": "METADATA"}],
				  "priority": "HIGH"
				}
				""");
		UUID earlierJob = createJob("""
				{
				  "inputUri": "s3://media-input/earlier.mp4",
				  "operations": [{"type": "THUMBNAIL"}],
				  "priority": "LOW"
				}
				""");
		UUID laterOp = operationId(laterJob);
		UUID earlierOp = operationId(earlierJob);
		jdbcTemplate.update(
				"update operations set created_at = ? where id = ?",
				Timestamp.from(Instant.parse("2026-08-25T10:01:00Z")),
				laterOp
		);
		jdbcTemplate.update(
				"update operations set created_at = ? where id = ?",
				Timestamp.from(Instant.parse("2026-08-25T10:00:00Z")),
				earlierOp
		);

		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations.length()").value(2))
				.andExpect(jsonPath("$.operations[0].operationId").value(earlierOp.toString()))
				.andExpect(jsonPath("$.operations[0].type").value("THUMBNAIL"))
				.andExpect(jsonPath("$.operations[0].inputUri").value("s3://media-input/earlier.mp4"))
				.andExpect(jsonPath("$.operations[1].operationId").value(laterOp.toString()))
				.andExpect(jsonPath("$.workers.length()").value(2))
				.andExpect(jsonPath("$.workers[0].id").value("worker-a"))
				.andExpect(jsonPath("$.workers[0].activeOperations").value(0))
				.andExpect(jsonPath("$.workers[1].id").value("worker-b"))
				.andExpect(jsonPath("$.workers[1].activeOperations").value(0));
	}

	@Test
	void snapshotTieBreaksByOperationOrderThenId() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/tie.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "THUMBNAIL"}
				  ]
				}
				""");
		UUID metadataId = UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ? and operation_type = 'METADATA'",
				String.class,
				jobId
		));
		UUID thumbnailId = UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ? and operation_type = 'THUMBNAIL'",
				String.class,
				jobId
		));
		Instant same = Instant.parse("2026-08-25T11:00:00Z");
		jdbcTemplate.update("update operations set created_at = ? where job_id = ?", Timestamp.from(same), jobId);

		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations[0].operationId").value(metadataId.toString()))
				.andExpect(jsonPath("$.operations[0].operationOrder").value(0))
				.andExpect(jsonPath("$.operations[1].operationId").value(thumbnailId.toString()))
				.andExpect(jsonPath("$.operations[1].operationOrder").value(1));
	}

	@Test
	void snapshotOmitsUnsupportedOperationTypes() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		createJob("""
				{
				  "inputUri": "s3://media-input/mixed.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "H264_TO_AV1"}
				  ]
				}
				""");

		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations.length()").value(1))
				.andExpect(jsonPath("$.operations[0].type").value("METADATA"));
	}

	@Test
	void snapshotIncludesTranscode1080pAndOmitsLaterTranscodes() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P");
		createJob("""
				{
				  "inputUri": "s3://media-input/mixed.mp4",
				  "operations": [
				    {"type": "AUDIO_EXTRACTION"},
				    {"type": "TRANSCODE_1080P"},
				    {"type": "TRANSCODE_4K_TO_1080P"},
				    {"type": "H264_TO_AV1"}
				  ]
				}
				""");

		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations.length()").value(2))
				.andExpect(jsonPath("$.operations[0].type").value("AUDIO_EXTRACTION"))
				.andExpect(jsonPath("$.operations[1].type").value("TRANSCODE_1080P"));
	}

	@Test
	void transcode1080pCannotBeAssignedToThumbnailOnlyWorker() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA", "THUMBNAIL");
		UUID operationId = operationId(createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "TRANSCODE_1080P"}]
				}
				"""));

		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WORKER_CAPABILITY_MISMATCH"));
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		assertThat(outboxCount(operationId)).isZero();
		assertThat(decisionCount(operationId)).isZero();
	}

	@Test
	void transcode1080pAssignsUnderLexicographicRoundRobinAndLeastLoaded() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P");
		WorkerTestSupport.register(mockMvc, "worker-b", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION", "TRANSCODE_1080P");
		UUID lex = operationId(createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"TRANSCODE_1080P"}]}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(lex, "worker-a", "FIFO", "LEXICOGRAPHIC")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.workerPolicy").value("LEXICOGRAPHIC"));

		UUID rr = operationId(createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"TRANSCODE_1080P"}]}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(rr, "worker-a", "FIFO", "ROUND_ROBIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.workerPolicy").value("ROUND_ROBIN"));

		UUID ll = operationId(createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"TRANSCODE_1080P"}]}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(ll, "worker-b", "FIFO", "LEAST_LOADED")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-b"))
				.andExpect(jsonPath("$.workerPolicy").value("LEAST_LOADED"));
	}

	@Test
	void audioExtractionCannotBeAssignedToThumbnailOnlyWorker() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA", "THUMBNAIL");
		UUID operationId = operationId(createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "AUDIO_EXTRACTION"}]
				}
				"""));

		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WORKER_CAPABILITY_MISMATCH"));
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		assertThat(outboxCount(operationId)).isZero();
		assertThat(decisionCount(operationId)).isZero();
	}

	@Test
	void audioExtractionAssignsUnderLexicographicRoundRobinAndLeastLoaded() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION");
		WorkerTestSupport.register(mockMvc, "worker-b", "METADATA", "THUMBNAIL", "AUDIO_EXTRACTION");
		UUID lex = operationId(createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"AUDIO_EXTRACTION"}]}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(lex, "worker-a", "FIFO", "LEXICOGRAPHIC")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.workerPolicy").value("LEXICOGRAPHIC"));

		UUID rr = operationId(createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"AUDIO_EXTRACTION"}]}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(rr, "worker-a", "FIFO", "ROUND_ROBIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.workerPolicy").value("ROUND_ROBIN"));

		UUID ll = operationId(createJob("""
				{"inputUri":"s3://media-input/video.mp4","operations":[{"type":"AUDIO_EXTRACTION"}]}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(ll, "worker-b", "FIFO", "LEAST_LOADED")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-b"))
				.andExpect(jsonPath("$.workerPolicy").value("LEAST_LOADED"));
	}

	@Test
	void assignCommitsQueuedToAssignedWithFifoDecisionAndTargetedOutbox() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-b");
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");
		UUID operationId = operationId(jobId);

		MvcResult assigned = mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationId").value(operationId.toString()))
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.policy").value("FIFO"))
				.andExpect(jsonPath("$.operationPolicy").value("FIFO"))
				.andExpect(jsonPath("$.workerPolicy").value("LEXICOGRAPHIC"))
				.andExpect(jsonPath("$.routingKey").value("worker.worker-a"))
				.andExpect(jsonPath("$.decisionId").isString())
				.andReturn();

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ASSIGNED"))
				.andExpect(jsonPath("$.operations[0].status").value("ASSIGNED"));

		String payload = jdbcTemplate.queryForObject(
				"select payload_json::text from dispatch_outbox where operation_id = ?",
				String.class,
				operationId
		);
		String decisionId = JsonPath.read(assigned.getResponse().getContentAsString(), "$.decisionId");
		assertThat(payload).contains("schemaVersion").contains("worker-a").contains("FIFO");
		assertThat(payload).contains(decisionId);
		assertThat(payload).contains("assignmentId");
		assertThat(payload).doesNotContain("attemptId");
		String routingKey = jdbcTemplate.queryForObject(
				"select routing_key from dispatch_outbox where operation_id = ?",
				String.class,
				operationId
		);
		assertThat(routingKey).isEqualTo("worker.worker-a");
		Integer decisions = jdbcTemplate.queryForObject(
				"select count(*) from scheduling_decisions where operation_id = ? and worker_id = 'worker-a' and policy = 'FIFO' and operation_policy = 'FIFO' and worker_policy = 'LEXICOGRAPHIC'",
				Integer.class,
				operationId
		);
		assertThat(decisions).isEqualTo(1);
		assertThat((String) JsonPath.read(assigned.getResponse().getContentAsString(), "$.decisionId")).isNotBlank();
		assertThat(jdbcTemplate.queryForObject(
				"select assigned_worker_id from operations where id = ?",
				String.class,
				operationId
		)).isEqualTo("worker-a");
		assertThat(jdbcTemplate.queryForObject(
				"select current_assignment_id::text from operations where id = ?",
				String.class,
				operationId
		)).isEqualTo(decisionId);
		assertThat(jdbcTemplate.queryForObject(
				"select assigned_at from operations where id = ?",
				Timestamp.class,
				operationId
		)).isNotNull();
	}

	@Test
	void thumbnailCannotBeAssignedToMetadataOnlyWorker() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a", "METADATA");
		UUID operationId = operationId(createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				"""));

		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WORKER_CAPABILITY_MISMATCH"));
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
		assertThat(outboxCount(operationId)).isZero();
		assertThat(decisionCount(operationId)).isZero();
	}

	@Test
	void assignRejectedWhenWorkerBecomesUnavailable() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID operationId = operationId(createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				"""));
		jdbcTemplate.update("update workers set status = 'UNAVAILABLE' where id = 'worker-a'");

		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WORKER_UNAVAILABLE"));
		assertThat(operationStatus(operationId)).isEqualTo("QUEUED");
	}

	@Test
	void assignRejectedWhenOperationIsNoLongerQueued() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID operationId = operationId(createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO")))
				.andExpect(status().isOk());

		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_OPERATION_STATE"));
	}

	@Test
	void concurrentAssignersCannotDoubleAssign() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID operationId = operationId(createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				"""));
		AssignOperationRequest first = new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC");
		AssignOperationRequest second = new AssignOperationRequest(operationId, "worker-b", "FIFO", "LEXICOGRAPHIC");
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> a = pool.submit(() -> {
				await(start);
				try {
					schedulerService.assign(first);
					successes.incrementAndGet();
				}
				catch (RuntimeException ex) {
					conflicts.incrementAndGet();
				}
			});
			Future<?> b = pool.submit(() -> {
				await(start);
				try {
					schedulerService.assign(second);
					successes.incrementAndGet();
				}
				catch (RuntimeException ex) {
					conflicts.incrementAndGet();
				}
			});
			start.countDown();
			a.get(10, TimeUnit.SECONDS);
			b.get(10, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(successes.get()).isEqualTo(1);
		assertThat(conflicts.get()).isEqualTo(1);
		assertThat(operationStatus(operationId)).isEqualTo("ASSIGNED");
		assertThat(decisionCount(operationId)).isEqualTo(1);
		assertThat(outboxCount(operationId)).isEqualTo(1);
	}

	@Test
	void requeuedInterruptedOperationIsAssignableAgain() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		UUID operationId = operationId(jobId);
		var assigned = schedulerService.assign(new AssignOperationRequest(operationId, "worker-a", "FIFO", "LEXICOGRAPHIC"));
		MvcResult started = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.startJson("worker-a", assigned.decisionId())))
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

		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations[0].operationId").value(operationId.toString()));

		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WORKER_UNAVAILABLE"));

		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-b", "FIFO")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-b"))
				.andExpect(jsonPath("$.policy").value("FIFO"));
		assertThat(decisionCount(operationId)).isEqualTo(2);
		String workerId = jdbcTemplate.queryForObject(
				"select worker_id from scheduling_decisions where operation_id = ? order by created_at desc limit 1",
				String.class,
				operationId
		);
		assertThat(workerId).isEqualTo("worker-b");
	}

	@Test
	void postJobsDoesNotAssignOrPublish() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		assertThat(jdbcTemplate.queryForObject("select count(*) from dispatch_outbox", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("select count(*) from scheduling_decisions", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from operations where status = 'QUEUED'",
				Integer.class
		)).isEqualTo(1);
	}

	@Test
	void roundRobinAssignRecordsBothPolicyDimensions() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID operationId = operationId(createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO", "ROUND_ROBIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.policy").value("FIFO"))
				.andExpect(jsonPath("$.operationPolicy").value("FIFO"))
				.andExpect(jsonPath("$.workerPolicy").value("ROUND_ROBIN"))
				.andExpect(jsonPath("$.workerId").value("worker-a"));
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from scheduling_decisions where operation_id = ? and operation_policy = 'FIFO' and worker_policy = 'ROUND_ROBIN' and worker_id = 'worker-a'",
				Integer.class,
				operationId
		)).isEqualTo(1);
		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roundRobinCursors.METADATA").value("worker-a"));
	}

	@Test
	void leastLoadedAssignRecordsBothPolicyDimensions() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b");
		UUID operationId = operationId(createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-b", "FIFO", "LEAST_LOADED")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.policy").value("FIFO"))
				.andExpect(jsonPath("$.operationPolicy").value("FIFO"))
				.andExpect(jsonPath("$.workerPolicy").value("LEAST_LOADED"))
				.andExpect(jsonPath("$.workerId").value("worker-b"));
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from scheduling_decisions where operation_id = ? and operation_policy = 'FIFO' and worker_policy = 'LEAST_LOADED' and worker_id = 'worker-b'",
				Integer.class,
				operationId
		)).isEqualTo(1);
	}

	@Test
	void unsupportedPolicyIsRejected() throws Exception {
		WorkerTestSupport.register(mockMvc, "worker-a");
		UUID operationId = operationId(createJob("""
				{
				  "inputUri": "s3://media-input/video.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				"""));
		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "ROUND_ROBIN", "LEXICOGRAPHIC")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_POLICY"));

		mockMvc.perform(post("/internal/scheduler/assign")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignJson(operationId, "worker-a", "FIFO", "SJF")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_POLICY"));
	}

	private UUID createJob(String json) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
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

	private static String assignJson(UUID operationId, String workerId, String policy) {
		return assignJson(operationId, workerId, policy, "LEXICOGRAPHIC");
	}

	private static String assignJson(UUID operationId, String workerId, String operationPolicy, String workerPolicy) {
		return """
				{"operationId":"%s","workerId":"%s","operationPolicy":"%s","workerPolicy":"%s"}
				""".formatted(operationId, workerId, operationPolicy, workerPolicy);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting to start concurrent assign");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}
}
