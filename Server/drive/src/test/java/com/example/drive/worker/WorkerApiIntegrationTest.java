package com.example.drive.worker;

import java.time.Instant;

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
class WorkerApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearWorkers() {
		jdbcTemplate.execute("delete from execution_attempts");
		jdbcTemplate.execute("delete from worker_supported_codecs");
		jdbcTemplate.execute("delete from worker_supported_operations");
		jdbcTemplate.execute("delete from workers");
	}

	@Test
	void firstRegistrationCreatesWorkerRow() throws Exception {
		mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson("worker-a", "METADATA", "THUMBNAIL", "h264", 8, 17179869184L)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.registeredAt").isString())
				.andExpect(jsonPath("$.updatedAt").isString())
				.andExpect(jsonPath("$.lastHeartbeat").isString());

		Integer workerCount = jdbcTemplate.queryForObject(
				"select count(*) from workers where id = 'worker-a' and status = 'AVAILABLE' and last_heartbeat is not null",
				Integer.class
		);
		Integer operationCount = jdbcTemplate.queryForObject(
				"select count(*) from worker_supported_operations where worker_id = 'worker-a'",
				Integer.class
		);
		Integer codecCount = jdbcTemplate.queryForObject(
				"select count(*) from worker_supported_codecs where worker_id = 'worker-a' and codec = 'h264'",
				Integer.class
		);
		assertThat(workerCount).isEqualTo(1);
		assertThat(operationCount).isEqualTo(2);
		assertThat(codecCount).isEqualTo(1);
	}

	@Test
	void reregistrationUpdatesCapabilitiesWithoutDuplicatingIdentity() throws Exception {
		MvcResult first = mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson("worker-a", "METADATA", "THUMBNAIL", "h264", 8, 17179869184L)))
				.andExpect(status().isCreated())
				.andReturn();
		Instant registeredAt = Instant.parse(JsonPath.read(first.getResponse().getContentAsString(), "$.registeredAt"));

		Thread.sleep(20);

		MvcResult updated = mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "workerId": "worker-a",
								  "hostname": "mac-worker-a-restarted",
								  "supportedOperations": ["METADATA"],
								  "supportedCodecs": ["h264", "av1"],
								  "cpuArchitecture": "arm64",
								  "cpuCores": 10,
								  "memoryBytes": 34359738368,
								  "ffmpegVersion": "8.1.2"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andReturn();

		Instant preservedRegisteredAt = Instant.parse(
				JsonPath.read(updated.getResponse().getContentAsString(), "$.registeredAt")
		);
		Instant updatedAt = Instant.parse(JsonPath.read(updated.getResponse().getContentAsString(), "$.updatedAt"));
		assertThat(preservedRegisteredAt).isEqualTo(registeredAt);
		assertThat(updatedAt).isAfterOrEqualTo(preservedRegisteredAt);

		Integer workerCount = jdbcTemplate.queryForObject("select count(*) from workers where id = 'worker-a'", Integer.class);
		assertThat(workerCount).isEqualTo(1);

		Integer cores = jdbcTemplate.queryForObject("select cpu_cores from workers where id = 'worker-a'", Integer.class);
		String hostname = jdbcTemplate.queryForObject("select hostname from workers where id = 'worker-a'", String.class);
		Integer operationCount = jdbcTemplate.queryForObject(
				"select count(*) from worker_supported_operations where worker_id = 'worker-a'",
				Integer.class
		);
		Integer codecCount = jdbcTemplate.queryForObject(
				"select count(*) from worker_supported_codecs where worker_id = 'worker-a'",
				Integer.class
		);
		assertThat(cores).isEqualTo(10);
		assertThat(hostname).isEqualTo("mac-worker-a-restarted");
		assertThat(operationCount).isEqualTo(1);
		assertThat(codecCount).isEqualTo(2);
	}

	@Test
	void getWorkersReturnsRegisteredWorkers() throws Exception {
		register("worker-a");
		register("worker-b");

		mockMvc.perform(get("/workers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workers.length()").value(2))
				.andExpect(jsonPath("$.workers[0].id").value("worker-a"))
				.andExpect(jsonPath("$.workers[0].status").value("AVAILABLE"))
				.andExpect(jsonPath("$.workers[0].hostname").value("mac-worker-a"))
				.andExpect(jsonPath("$.workers[0].supportedOperations[0]").value("METADATA"))
				.andExpect(jsonPath("$.workers[0].supportedOperations[1]").value("THUMBNAIL"))
				.andExpect(jsonPath("$.workers[0].supportedCodecs[0]").value("h264"))
				.andExpect(jsonPath("$.workers[0].cpuArchitecture").value("arm64"))
				.andExpect(jsonPath("$.workers[0].cpuCores").value(8))
				.andExpect(jsonPath("$.workers[0].memoryBytes").value(17179869184L))
				.andExpect(jsonPath("$.workers[0].ffmpegVersion").value("7.1"))
				.andExpect(jsonPath("$.workers[0].lastHeartbeat").isString())
				.andExpect(jsonPath("$.workers[1].id").value("worker-b"));
	}

	@Test
	void getWorkerByIdReturnsDetailAndUnknownWorkerIs404() throws Exception {
		register("worker-a");

		mockMvc.perform(get("/workers/worker-a"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("worker-a"))
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.lastHeartbeat").isString());

		mockMvc.perform(get("/workers/missing-worker"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("WORKER_NOT_FOUND"));
	}

	@Test
	void duplicateCapabilityEntriesAreNormalized() throws Exception {
		mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "workerId": "worker-dup",
								  "hostname": "host-dup",
								  "supportedOperations": ["METADATA", "METADATA", "THUMBNAIL"],
								  "supportedCodecs": ["h264", "H264", "hevc"],
								  "cpuArchitecture": "arm64",
								  "cpuCores": 4,
								  "memoryBytes": 1024,
								  "ffmpegVersion": "7.1"
								}
								"""))
				.andExpect(status().isCreated());

		Integer operationCount = jdbcTemplate.queryForObject(
				"select count(*) from worker_supported_operations where worker_id = 'worker-dup'",
				Integer.class
		);
		Integer codecCount = jdbcTemplate.queryForObject(
				"select count(*) from worker_supported_codecs where worker_id = 'worker-dup'",
				Integer.class
		);
		assertThat(operationCount).isEqualTo(2);
		assertThat(codecCount).isEqualTo(2);

		mockMvc.perform(get("/workers/worker-dup"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.supportedOperations.length()").value(2))
				.andExpect(jsonPath("$.supportedCodecs.length()").value(2));
	}

	@Test
	void invalidRegistrationReturns400() throws Exception {
		mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson(" ", "METADATA", "THUMBNAIL", "h264", 8, 1024L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REGISTRATION"));

		mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "workerId": "worker-empty-ops",
								  "hostname": "host",
								  "supportedOperations": [],
								  "supportedCodecs": ["h264"],
								  "cpuArchitecture": "arm64",
								  "cpuCores": 8,
								  "memoryBytes": 1024,
								  "ffmpegVersion": "7.1"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REGISTRATION"));

		mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson("worker-cores", "METADATA", "THUMBNAIL", "h264", 0, 1024L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REGISTRATION"));

		mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson("worker-mem", "METADATA", "THUMBNAIL", "h264", 8, -1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REGISTRATION"));
	}

	@Test
	void malformedCapabilityReturns400() throws Exception {
		mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "workerId": "worker-bad-op",
								  "hostname": "host",
								  "supportedOperations": ["NOT_A_REAL_OPERATION"],
								  "supportedCodecs": ["h264"],
								  "cpuArchitecture": "arm64",
								  "cpuCores": 8,
								  "memoryBytes": 1024,
								  "ffmpegVersion": "7.1"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_WORKER_CAPABILITY"));

		mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "workerId": "worker-bad-codec",
								  "hostname": "host",
								  "supportedOperations": ["METADATA"],
								  "supportedCodecs": ["mpeg2"],
								  "cpuArchitecture": "arm64",
								  "cpuCores": 8,
								  "memoryBytes": 1024,
								  "ffmpegVersion": "7.1"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_WORKER_CAPABILITY"));
	}

	@Test
	void heartbeatRefreshesLiveness() throws Exception {
		MvcResult created = mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson("worker-a", "METADATA", "THUMBNAIL", "h264", 8, 17179869184L)))
				.andExpect(status().isCreated())
				.andReturn();
		Instant registeredHeartbeat = Instant.parse(
				JsonPath.read(created.getResponse().getContentAsString(), "$.lastHeartbeat")
		);
		Instant registeredUpdatedAt = Instant.parse(
				JsonPath.read(created.getResponse().getContentAsString(), "$.updatedAt")
		);

		Thread.sleep(20);

		MvcResult heartbeat = mockMvc.perform(post("/internal/workers/worker-a/heartbeat"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workerId").value("worker-a"))
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andReturn();
		Instant lastHeartbeat = Instant.parse(
				JsonPath.read(heartbeat.getResponse().getContentAsString(), "$.lastHeartbeat")
		);
		assertThat(lastHeartbeat).isAfterOrEqualTo(registeredHeartbeat);

		MvcResult detail = mockMvc.perform(get("/workers/worker-a"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andReturn();
		Instant updatedAt = Instant.parse(JsonPath.read(detail.getResponse().getContentAsString(), "$.updatedAt"));
		Instant persistedHeartbeat = Instant.parse(
				JsonPath.read(detail.getResponse().getContentAsString(), "$.lastHeartbeat")
		);
		assertThat(persistedHeartbeat).isEqualTo(lastHeartbeat);
		assertThat(updatedAt).isAfterOrEqualTo(registeredUpdatedAt);
	}

	@Test
	void heartbeatOfUnknownWorkerReturns404() throws Exception {
		mockMvc.perform(post("/internal/workers/missing-worker/heartbeat"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("WORKER_NOT_FOUND"));
	}

	private void register(String workerId) throws Exception {
		mockMvc.perform(post("/internal/workers/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson(workerId, "METADATA", "THUMBNAIL", "h264", 8, 17179869184L)))
				.andExpect(status().isCreated());
	}

	private static String registrationJson(
			String workerId,
			String operationA,
			String operationB,
			String codec,
			int cpuCores,
			long memoryBytes
	) {
		return """
				{
				  "workerId": "%s",
				  "hostname": "mac-%s",
				  "supportedOperations": ["%s", "%s"],
				  "supportedCodecs": ["%s"],
				  "cpuArchitecture": "arm64",
				  "cpuCores": %d,
				  "memoryBytes": %d,
				  "ffmpegVersion": "7.1"
				}
				""".formatted(workerId, workerId, operationA, operationB, codec, cpuCores, memoryBytes);
	}
}
