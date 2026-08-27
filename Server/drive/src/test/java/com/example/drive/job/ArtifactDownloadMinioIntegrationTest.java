package com.example.drive.job;

import com.example.drive.support.AuthenticatedApiTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.example.drive.support.ControlServiceTest;
import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
@Testcontainers
class ArtifactDownloadMinioIntegrationTest extends AuthenticatedApiTest {

	private static final String ACCESS_KEY = "minioaccess";
	private static final String SECRET_KEY = "miniosecretvalue";
	private static final byte[] PAYLOAD = "phase-5d-thumbnail-bytes".getBytes(StandardCharsets.UTF_8);

	@Container
	static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-12-18T13-15-44Z"))
			.withCommand("server", "/data")
			.withEnv("MINIO_ROOT_USER", ACCESS_KEY)
			.withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
			.withEnv("MINIO_CI_CD", "on")
			.withExposedPorts(9000)
			.waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200))
			.withStartupTimeout(Duration.ofMinutes(2));

	@DynamicPropertySource
	static void objectStoreProperties(DynamicPropertyRegistry registry) {
		registry.add("drive.object-store.endpoint", ArtifactDownloadMinioIntegrationTest::endpoint);
		registry.add("drive.object-store.region", () -> "us-east-1");
		registry.add("drive.object-store.access-key", () -> ACCESS_KEY);
		registry.add("drive.object-store.secret-key", () -> SECRET_KEY);
		registry.add("drive.object-store.force-path-style", () -> "true");
		registry.add("drive.artifact-access.url-ttl", () -> "15m");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearJobs() {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
		jdbcTemplate.update("delete from scheduling_decisions");
		jdbcTemplate.update("delete from dispatch_outbox");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
	}

	@Test
	void presignedUrlDownloadsMatchingBytes() throws Exception {
		UUID jobId = createJob();
		UUID operationId = operationId(jobId);
		String key = "jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg";
		putObject(key, PAYLOAD);
		UUID artifactId = insertArtifact(jobId, operationId, key, PAYLOAD.length, sha256(PAYLOAD));

		MvcResult result = mockMvc.perform(authed(post("/jobs/" + jobId + "/artifacts/" + artifactId + "/download-url")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.artifactId").value(artifactId.toString()))
				.andExpect(jsonPath("$.url").isString())
				.andExpect(jsonPath("$.expiresAt").isString())
				.andExpect(jsonPath("$.fileName").value("thumbnail.jpg"))
				.andReturn();

		String url = JsonPath.read(result.getResponse().getContentAsString(), "$.url");
		assertThat(url).startsWith(endpoint() + "/media-output/").contains("X-Amz-Signature");
		assertThat(url).doesNotContain(SECRET_KEY);

		Instant expiresAt = Instant.parse(JsonPath.read(result.getResponse().getContentAsString(), "$.expiresAt"));
		assertThat(expiresAt).isAfter(Instant.now().plus(Duration.ofMinutes(10)));
		assertThat(expiresAt).isBefore(Instant.now().plus(Duration.ofMinutes(20)));

		HttpResponse<byte[]> downloaded = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray()
		);
		assertThat(downloaded.statusCode()).isEqualTo(200);
		assertThat(downloaded.body()).isEqualTo(PAYLOAD);
		assertThat(sha256(downloaded.body())).isEqualTo(sha256(PAYLOAD));
	}

	@Test
	void missingObjectReturns404() throws Exception {
		UUID jobId = createJob();
		UUID operationId = operationId(jobId);
		String key = "jobs/" + jobId + "/operations/" + operationId + "/missing.jpg";
		ensureBucket();
		UUID artifactId = insertArtifact(jobId, operationId, key, 1L, sha256(PAYLOAD));

		mockMvc.perform(authed(post("/jobs/" + jobId + "/artifacts/" + artifactId + "/download-url")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OBJECT_NOT_FOUND"))
				.andExpect(jsonPath("$.url").doesNotExist());
	}

	private static String endpoint() {
		return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
	}

	private UUID createJob() throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"inputUri":"s3://media-input/clip.mp4","operations":[{"type":"THUMBNAIL"}]}
								"""))
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

	private UUID insertArtifact(UUID jobId, UUID operationId, String key, long sizeBytes, String checksum) {
		UUID artifactId = UUID.randomUUID();
		jdbcTemplate.update("""
						insert into artifacts (
						  id, job_id, operation_id, artifact_type, object_uri, content_type, size_bytes, checksum, created_at
						) values (?, ?, ?, 'THUMBNAIL', ?, 'image/jpeg', ?, ?, ?)
						""",
				artifactId,
				jobId,
				operationId,
				"s3://media-output/" + key,
				sizeBytes,
				checksum,
				Timestamp.from(Instant.parse("2026-08-20T00:00:00Z"))
		);
		return artifactId;
	}

	private static void ensureBucket() {
		try (S3Client client = s3Client()) {
			if (client.listBuckets().buckets().stream().noneMatch(bucket -> "media-output".equals(bucket.name()))) {
				client.createBucket(CreateBucketRequest.builder().bucket("media-output").build());
			}
		}
	}

	private static void putObject(String key, byte[] payload) {
		ensureBucket();
		try (S3Client client = s3Client()) {
			client.putObject(
					PutObjectRequest.builder()
							.bucket("media-output")
							.key(key)
							.contentType("image/jpeg")
							.build(),
					RequestBody.fromBytes(payload)
			);
		}
	}

	private static S3Client s3Client() {
		return S3Client.builder()
				.endpointOverride(URI.create(endpoint()))
				.region(Region.US_EAST_1)
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
				))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
				.httpClientBuilder(UrlConnectionHttpClient.builder())
				.build();
	}

	private static String sha256(byte[] payload) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
		return "sha256:" + HexFormat.of().formatHex(digest);
	}
}
