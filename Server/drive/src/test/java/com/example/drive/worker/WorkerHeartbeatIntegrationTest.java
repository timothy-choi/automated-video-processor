package com.example.drive.worker;

import com.example.drive.support.AuthenticatedApiTest;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.support.ControlServiceTest;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
class WorkerHeartbeatIntegrationTest extends AuthenticatedApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private WorkerService workerService;

	@Autowired
	private Clock clock;

	@BeforeEach
	void clearTables() {
		jdbcTemplate.execute("delete from worker_supported_codecs");
		jdbcTemplate.execute("delete from worker_supported_operations");
		jdbcTemplate.execute("delete from artifacts");
		jdbcTemplate.execute("delete from execution_attempts");
		jdbcTemplate.execute("delete from operations");
		jdbcTemplate.execute("delete from jobs");
		jdbcTemplate.execute("delete from workers");
	}

	@Test
	void registrationInitializesAvailabilityAndHeartbeat() throws Exception {
		MvcResult created = register("worker-a");
		Instant lastHeartbeat = Instant.parse(
				JsonPath.read(created.getResponse().getContentAsString(), "$.lastHeartbeat")
		);
		assertThat(lastHeartbeat).isNotNull();

		mockMvc.perform(authed(get("/workers/worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.lastHeartbeat").isString());
	}

	@Test
	void staleAvailableWorkerBecomesUnavailable() throws Exception {
		register("stale-worker");
		backdateHeartbeat("stale-worker", Duration.ofSeconds(60));

		int marked = workerService.markStaleWorkers();
		assertThat(marked).isEqualTo(1);

		mockMvc.perform(authed(get("/workers/stale-worker")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UNAVAILABLE"));
	}

	@Test
	void freshWorkerRemainsAvailable() throws Exception {
		register("fresh-worker");

		int marked = workerService.markStaleWorkers();
		assertThat(marked).isEqualTo(0);

		mockMvc.perform(authed(get("/workers/fresh-worker")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.lastHeartbeat").isString());
	}

	@Test
	void heartbeatRecoversUnavailableWorker() throws Exception {
		register("worker-a");
		backdateHeartbeat("worker-a", Duration.ofSeconds(60));
		assertThat(workerService.markStaleWorkers()).isEqualTo(1);

		mockMvc.perform(post("/internal/workers/worker-a/heartbeat"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.lastHeartbeat").isString());

		mockMvc.perform(authed(get("/workers/worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AVAILABLE"));
	}

	@Test
	void registrationRecoversUnavailableWorkerWithoutDuplicatingRow() throws Exception {
		MvcResult first = register("worker-a");
		Instant registeredAt = Instant.parse(
				JsonPath.read(first.getResponse().getContentAsString(), "$.registeredAt")
		);
		backdateHeartbeat("worker-a", Duration.ofSeconds(60));
		assertThat(workerService.markStaleWorkers()).isEqualTo(1);

		MvcResult restored = mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andReturn();
		Instant preserved = Instant.parse(
				JsonPath.read(restored.getResponse().getContentAsString(), "$.registeredAt")
		);
		assertThat(preserved).isEqualTo(registeredAt);

		Integer workerCount = jdbcTemplate.queryForObject(
				"select count(*) from workers where id = 'worker-a'",
				Integer.class
		);
		assertThat(workerCount).isEqualTo(1);
	}

	@Test
	void nullLastHeartbeatOnAvailableWorkerIsStale() {
		Instant now = clock.instant();
		jdbcTemplate.update("""
				insert into workers (
				  id, hostname, status, cpu_architecture, cpu_cores, memory_bytes,
				  ffmpeg_version, last_heartbeat, registered_at, updated_at
				) values (
				  'legacy-available', 'host', 'AVAILABLE', 'arm64', 1, 1024,
				  null, null, ?, ?
				)
				""", Timestamp.from(now), Timestamp.from(now));

		assertThat(workerService.markStaleWorkers()).isEqualTo(1);
		String status = jdbcTemplate.queryForObject(
				"select status from workers where id = 'legacy-available'",
				String.class
		);
		assertThat(status).isEqualTo("UNAVAILABLE");
	}

	@Test
	void migratedUnavailableNullHeartbeatStaysUnavailableUntilHeartbeat() throws Exception {
		Instant now = clock.instant();
		jdbcTemplate.update("""
				insert into workers (
				  id, hostname, status, cpu_architecture, cpu_cores, memory_bytes,
				  ffmpeg_version, last_heartbeat, registered_at, updated_at
				) values (
				  'migrated', 'host', 'UNAVAILABLE', 'arm64', 1, 1024,
				  null, null, ?, ?
				)
				""", Timestamp.from(now), Timestamp.from(now));

		assertThat(workerService.markStaleWorkers()).isEqualTo(0);
		mockMvc.perform(authed(get("/workers/migrated")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.lastHeartbeat").value(org.hamcrest.Matchers.nullValue()));

		mockMvc.perform(post("/internal/workers/migrated/heartbeat"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.lastHeartbeat").isString());
	}

	@Test
	void concurrentHeartbeatWinsOverStaleDetector() throws Exception {
		register("worker-race");
		backdateHeartbeat("worker-race", Duration.ofSeconds(60));

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<?> detector = pool.submit(() -> {
				ready.countDown();
				start.await();
				workerService.markStaleWorkers();
				return null;
			});
			Future<?> heartbeat = pool.submit(() -> {
				ready.countDown();
				start.await();
				mockMvc.perform(post("/internal/workers/worker-race/heartbeat"))
						.andExpect(status().isOk());
				return null;
			});
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			detector.get(5, TimeUnit.SECONDS);
			heartbeat.get(5, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}

		mockMvc.perform(authed(get("/workers/worker-race")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AVAILABLE"));
		Instant lastHeartbeat = jdbcTemplate.queryForObject(
				"select last_heartbeat from workers where id = 'worker-race'",
				Timestamp.class
		).toInstant();
		assertThat(lastHeartbeat).isAfter(clock.instant().minus(Duration.ofSeconds(15)));
	}

	@Test
	void markingWorkerUnavailableDoesNotReassignRunningWork() throws Exception {
		register("worker-a");
		mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "file:///tmp/sample.mp4",
								  "operations": [{"type": "METADATA"}]
								}
								"""))
				.andExpect(status().isAccepted());
		MvcResult claimed = mockMvc.perform(post("/internal/operations/claim")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"workerId": "worker-a"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andReturn();
		UUID operationId = UUID.fromString(
				JsonPath.read(claimed.getResponse().getContentAsString(), "$.operationId")
		);

		backdateHeartbeat("worker-a", Duration.ofSeconds(60));
		assertThat(workerService.markStaleWorkers()).isEqualTo(1);

		String operationStatus = jdbcTemplate.queryForObject(
				"select status from operations where id = ?",
				String.class,
				operationId
		);
		assertThat(operationStatus).isEqualTo("RUNNING");
		mockMvc.perform(authed(get("/workers/worker-a")))
				.andExpect(jsonPath("$.status").value("UNAVAILABLE"));
	}

	private MvcResult register(String workerId) throws Exception {
		return mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson(workerId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.lastHeartbeat").isString())
				.andReturn();
	}

	private void backdateHeartbeat(String workerId, Duration age) {
		Instant stale = clock.instant().minus(age);
		jdbcTemplate.update(
				"update workers set last_heartbeat = ? where id = ?",
				Timestamp.from(stale),
				workerId
		);
	}

	private static String registrationJson(String workerId) {
		return """
				{
				  "workerId": "%s",
				  "hostname": "mac-%s",
				  "supportedOperations": ["METADATA", "THUMBNAIL"],
				  "supportedCodecs": ["h264"],
				  "cpuArchitecture": "arm64",
				  "cpuCores": 8,
				  "memoryBytes": 17179869184,
				  "ffmpegVersion": "7.1"
				}
				""".formatted(workerId, workerId);
	}
}
