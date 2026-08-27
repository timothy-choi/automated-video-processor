package com.example.drive.job;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.storage.ObjectNotFoundException;
import com.example.drive.storage.ObjectStoreUnavailableException;
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
class ArtifactAccessApiIntegrationTest {

	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StubObjectStoreAccess objectStoreAccess;

	@BeforeEach
	void reset() {
		objectStoreAccess.reset();
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
		jdbcTemplate.update("delete from scheduling_decisions");
		jdbcTemplate.update("delete from dispatch_outbox");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
	}

	@Test
	void downloadUrlPresignsS3ObjectAndReturnsExpiry() throws Exception {
		Created created = createThumbnailArtifact();

		mockMvc.perform(post("/jobs/" + created.jobId() + "/artifacts/" + created.artifactId() + "/download-url"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifactId").value(created.artifactId().toString()))
				.andExpect(jsonPath("$.url").value(
						"http://localhost:9000/media-output/jobs/example/thumbnail.jpg?X-Amz-Signature=test"
				))
				.andExpect(jsonPath("$.expiresAt").value("2026-08-27T02:15:00Z"))
				.andExpect(jsonPath("$.contentType").value("image/jpeg"))
				.andExpect(jsonPath("$.fileName").value("thumbnail.jpg"))
				.andExpect(jsonPath("$.accessKey").doesNotExist())
				.andExpect(jsonPath("$.secretKey").doesNotExist())
				.andExpect(jsonPath("$.endpoint").doesNotExist());

		assertThat(objectStoreAccess.lastBucket()).isEqualTo("media-output");
		assertThat(objectStoreAccess.lastKey()).isEqualTo(
				"jobs/" + created.jobId() + "/operations/" + created.operationId() + "/thumbnail.jpg"
		);
		assertThat(objectStoreAccess.lastTtl()).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void downloadUrlDoesNotPersistSignedUrlOrChangeArtifactMetadata() throws Exception {
		Created created = createThumbnailArtifact();
		mockMvc.perform(post("/jobs/" + created.jobId() + "/artifacts/" + created.artifactId() + "/download-url"))
				.andExpect(status().isOk());

		String storedUri = jdbcTemplate.queryForObject(
				"select object_uri from artifacts where id = ?",
				String.class,
				created.artifactId()
		);
		assertThat(storedUri).startsWith("s3://").doesNotContain("X-Amz-Signature");

		mockMvc.perform(get("/jobs/" + created.jobId() + "/artifacts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifacts.length()").value(1))
				.andExpect(jsonPath("$.artifacts[0].id").value(created.artifactId().toString()))
				.andExpect(jsonPath("$.artifacts[0].objectUri").value(storedUri))
				.andExpect(jsonPath("$.artifacts[0].url").doesNotExist())
				.andExpect(jsonPath("$.artifacts[0].expiresAt").doesNotExist());

		mockMvc.perform(get("/jobs/" + created.jobId() + "/artifacts/" + created.artifactId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").doesNotExist())
				.andExpect(jsonPath("$.objectUri").value(storedUri));
	}

	@Test
	void unknownJobReturns404() throws Exception {
		mockMvc.perform(post("/jobs/" + UUID.randomUUID() + "/artifacts/" + UUID.randomUUID() + "/download-url"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
	}

	@Test
	void missingAndWrongJobArtifactReturn404() throws Exception {
		Created created = createThumbnailArtifact();
		UUID otherJob = createJob("""
				{"inputUri":"s3://media-input/other.mp4","operations":[{"type":"METADATA"}]}
				""");

		mockMvc.perform(post("/jobs/" + created.jobId() + "/artifacts/" + UUID.randomUUID() + "/download-url"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ARTIFACT_NOT_FOUND"));

		mockMvc.perform(post("/jobs/" + otherJob + "/artifacts/" + created.artifactId() + "/download-url"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ARTIFACT_NOT_FOUND"));
	}

	@Test
	void invalidStoredUriReturns400() throws Exception {
		Created created = createThumbnailArtifact();
		jdbcTemplate.update("update artifacts set object_uri = ? where id = ?", "file:///tmp/x.jpg", created.artifactId());

		mockMvc.perform(post("/jobs/" + created.jobId() + "/artifacts/" + created.artifactId() + "/download-url"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ARTIFACT_URI_INVALID"));
	}

	@Test
	void unsupportedSchemeReturns400() throws Exception {
		Created created = createThumbnailArtifact();
		jdbcTemplate.update(
				"update artifacts set object_uri = ? where id = ?",
				"https://example.com/thumbnail.jpg",
				created.artifactId()
		);

		mockMvc.perform(post("/jobs/" + created.jobId() + "/artifacts/" + created.artifactId() + "/download-url"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ARTIFACT_URI_INVALID"));
	}

	@Test
	void missingObjectReturns404WithoutIssuingUrl() throws Exception {
		Created created = createThumbnailArtifact();
		objectStoreAccess.failVerify(new ObjectNotFoundException("media-output", "missing"));

		mockMvc.perform(post("/jobs/" + created.jobId() + "/artifacts/" + created.artifactId() + "/download-url"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OBJECT_NOT_FOUND"))
				.andExpect(jsonPath("$.url").doesNotExist());
		assertThat(objectStoreAccess.lastTtl()).isNull();
	}

	@Test
	void objectStoreUnavailableReturns503() throws Exception {
		Created created = createThumbnailArtifact();
		objectStoreAccess.failVerify(new ObjectStoreUnavailableException("Object store is unavailable"));

		mockMvc.perform(post("/jobs/" + created.jobId() + "/artifacts/" + created.artifactId() + "/download-url"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("OBJECT_STORE_UNAVAILABLE"))
				.andExpect(jsonPath("$.url").doesNotExist());
	}

	private Created createThumbnailArtifact() throws Exception {
		UUID jobId = createJob("""
				{"inputUri":"s3://media-input/clip.mp4","operations":[{"type":"THUMBNAIL"}]}
				""");
		UUID operationId = UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ?",
				String.class,
				jobId
		));
		UUID artifactId = UUID.randomUUID();
		String objectUri = "s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg";
		jdbcTemplate.update("""
						insert into artifacts (
						  id, job_id, operation_id, artifact_type, object_uri, content_type, size_bytes, checksum, created_at
						) values (?, ?, ?, 'THUMBNAIL', ?, 'image/jpeg', 1234, ?, ?)
						""",
				artifactId,
				jobId,
				operationId,
				objectUri,
				SHA256,
				Timestamp.from(Instant.parse("2026-08-20T00:00:00Z"))
		);
		return new Created(jobId, operationId, artifactId);
	}

	private UUID createJob(String body) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}

	private record Created(UUID jobId, UUID operationId, UUID artifactId) {
	}
}
