package com.example.drive.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.support.AuthTestSupport;
import com.example.drive.support.AuthenticatedApiTest;
import com.example.drive.support.ControlServiceTest;
import com.example.drive.support.StubObjectStoreAccess;
import com.example.drive.support.StubObjectStoreAccessConfig;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
@Import(StubObjectStoreAccessConfig.class)
class AccountIsolationIntegrationTest extends AuthenticatedApiTest {

	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StubObjectStoreAccess objectStoreAccess;

	private AuthTestSupport.TestAccount accountA;
	private AuthTestSupport.TestAccount accountB;
	private UUID jobA;
	private UUID jobB;
	private UUID operationA;
	private UUID operationB;
	private UUID artifactA;
	private UUID artifactB;

	@BeforeEach
	void twoAccountsAndJobs() throws Exception {
		objectStoreAccess.reset();
		accountA = account;
		accountB = AuthTestSupport.createAccount(mockMvc, "account-b");
		jobA = createJob(accountA, "s3://media-input/a.mp4");
		jobB = createJob(accountB, "s3://media-input/b.mp4");
		operationA = operationId(jobA);
		operationB = operationId(jobB);
		artifactA = insertArtifact(jobA, operationA);
		artifactB = insertArtifact(jobB, operationB);
		jdbcTemplate.update("update operations set status = 'FAILED' where id = ?", operationB);
		jdbcTemplate.update("update jobs set status = 'FAILED' where id = ?", jobB);
		jdbcTemplate.update("update operations set status = 'FAILED' where id = ?", operationA);
		jdbcTemplate.update("update jobs set status = 'FAILED' where id = ?", jobA);
	}

	@Test
	void listingIsOwnerScopedIncludingTotalElements() throws Exception {
		mockMvc.perform(authed(get("/jobs"), accountA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(jobA.toString()));

		mockMvc.perform(authed(get("/jobs"), accountB))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(jobB.toString()));
	}

	@Test
	void listingFiltersRemainOwnerScoped() throws Exception {
		UUID extraA = createJob(accountA, "s3://media-input/a-queued.mp4");
		mockMvc.perform(authed(get("/jobs").param("status", "FAILED"), accountA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].id").value(jobA.toString()));

		mockMvc.perform(authed(get("/jobs").param("status", "QUEUED"), accountA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].id").value(extraA.toString()));

		mockMvc.perform(authed(get("/jobs").param("status", "QUEUED"), accountB))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void jobDetailIsIsolated() throws Exception {
		mockMvc.perform(authed(get("/jobs/" + jobA), accountA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(jobA.toString()));

		mockMvc.perform(authed(get("/jobs/" + jobB), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
	}

	@Test
	void operationsAndAttemptsAreIsolated() throws Exception {
		mockMvc.perform(authed(get("/jobs/" + jobA + "/operations"), accountA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations.length()").value(1));

		mockMvc.perform(authed(get("/jobs/" + jobB + "/operations"), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

		mockMvc.perform(authed(get("/jobs/" + jobA + "/operations/" + operationA + "/attempts"), accountA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.attempts.length()").value(0));

		mockMvc.perform(authed(get("/jobs/" + jobB + "/operations/" + operationB + "/attempts"), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
	}

	@Test
	void artifactsAreIsolated() throws Exception {
		mockMvc.perform(authed(get("/jobs/" + jobA + "/artifacts"), accountA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(1))
				.andExpect(jsonPath("$.artifacts[0].id").value(artifactA.toString()));

		mockMvc.perform(authed(get("/jobs/" + jobB + "/artifacts"), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

		mockMvc.perform(authed(get("/jobs/" + jobB + "/artifacts/" + artifactB), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
	}

	@Test
	void downloadUrlIsIsolatedAndDoesNotPresignOnRejection() throws Exception {
		mockMvc.perform(authed(post("/jobs/" + jobA + "/artifacts/" + artifactA + "/download-url"), accountA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").isString());
		assertThat(objectStoreAccess.lastKey()).isNotNull();
		objectStoreAccess.reset();

		mockMvc.perform(authed(post("/jobs/" + jobB + "/artifacts/" + artifactB + "/download-url"), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
				.andExpect(jsonPath("$.url").doesNotExist());
		assertThat(objectStoreAccess.lastBucket()).isNull();
		assertThat(objectStoreAccess.lastKey()).isNull();
		assertThat(objectStoreAccess.lastTtl()).isNull();
	}

	@Test
	void cancelAndRetryAreIsolated() throws Exception {
		mockMvc.perform(authed(post("/jobs/" + jobB + "/cancel"), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
		assertThat(jobStatus(jobB)).isEqualTo("FAILED");

		mockMvc.perform(authed(post("/jobs/" + jobB + "/operations/" + operationB + "/cancel"), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

		mockMvc.perform(authed(post("/jobs/" + jobB + "/retry"), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
		assertThat(operationStatus(operationB)).isEqualTo("FAILED");

		mockMvc.perform(authed(post("/jobs/" + jobB + "/operations/" + operationB + "/retry"), accountA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
		assertThat(operationStatus(operationB)).isEqualTo("FAILED");

		mockMvc.perform(authed(post("/jobs/" + jobA + "/retry"), accountA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(jobA.toString()));
		assertThat(operationStatus(operationA)).isEqualTo("QUEUED");
	}

	private UUID createJob(AuthTestSupport.TestAccount owner, String inputUri) throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/jobs"), owner)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"inputUri":"%s","operations":[{"type":"THUMBNAIL"}]}
								""".formatted(inputUri)))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}

	private UUID operationId(UUID jobId) {
		return UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ?",
				String.class,
				jobId
		));
	}

	private UUID insertArtifact(UUID jobId, UUID operationId) {
		UUID artifactId = UUID.randomUUID();
		jdbcTemplate.update("""
						insert into artifacts (
						  id, job_id, operation_id, artifact_type, object_uri, content_type, size_bytes, checksum, created_at
						) values (?, ?, ?, 'THUMBNAIL', ?, 'image/jpeg', 1234, ?, ?)
						""",
				artifactId,
				jobId,
				operationId,
				"s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg",
				SHA256,
				Timestamp.from(Instant.parse("2026-08-20T00:00:00Z"))
		);
		return artifactId;
	}

	private String jobStatus(UUID jobId) {
		return jdbcTemplate.queryForObject("select status from jobs where id = ?", String.class, jobId);
	}

	private String operationStatus(UUID operationId) {
		return jdbcTemplate.queryForObject("select status from operations where id = ?", String.class, operationId);
	}
}
