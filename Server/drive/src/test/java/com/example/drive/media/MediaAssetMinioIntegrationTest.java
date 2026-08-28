package com.example.drive.media;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

import com.example.drive.support.AuthenticatedApiTest;
import com.example.drive.support.ControlServiceTest;
import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
@Testcontainers
class MediaAssetMinioIntegrationTest extends AuthenticatedApiTest {

	private static final String ACCESS_KEY = "minioaccess";
	private static final String SECRET_KEY = "miniosecretvalue";
	private static final byte[] PAYLOAD = "phase-6d-source-bytes".getBytes(StandardCharsets.UTF_8);

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
		registry.add("drive.object-store.endpoint", MediaAssetMinioIntegrationTest::endpoint);
		registry.add("drive.object-store.public-endpoint", MediaAssetMinioIntegrationTest::endpoint);
		registry.add("drive.object-store.region", () -> "us-east-1");
		registry.add("drive.object-store.access-key", () -> ACCESS_KEY);
		registry.add("drive.object-store.secret-key", () -> SECRET_KEY);
		registry.add("drive.object-store.force-path-style", () -> "true");
		registry.add("drive.media-upload.url-ttl", () -> "15m");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearRows() {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
		jdbcTemplate.update("delete from scheduling_decisions");
		jdbcTemplate.update("delete from dispatch_outbox");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
		jdbcTemplate.update("delete from media_assets");
		ensureBucket();
	}

	@Test
	void presignedPutThenCompleteMarksReadyWithActualSize() throws Exception {
		MvcResult created = mockMvc.perform(authed(post("/media-assets"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"filename":"clip.mp4","contentType":"video/mp4","sizeBytes":%d}
								""".formatted(PAYLOAD.length)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.mediaAsset.status").value("PENDING_UPLOAD"))
				.andReturn();

		UUID assetId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.mediaAsset.id"));
		String uploadUrl = JsonPath.read(created.getResponse().getContentAsString(), "$.upload.url");
		assertThat(uploadUrl).startsWith(endpoint() + "/media-input/");
		assertThat(uploadUrl).contains("X-Amz-Signature");
		assertThat(uploadUrl).doesNotContain(SECRET_KEY);
		assertThat(uploadUrl).contains("X-Amz-Expires");

		HttpResponse<byte[]> uploaded = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(uploadUrl))
						.header("Content-Type", "video/mp4")
						.PUT(HttpRequest.BodyPublishers.ofByteArray(PAYLOAD))
						.build(),
				HttpResponse.BodyHandlers.ofByteArray()
		);
		assertThat(uploaded.statusCode()).isBetween(200, 299);

		mockMvc.perform(authed(post("/media-assets/" + assetId + "/complete")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.sizeBytes").value(PAYLOAD.length))
				.andExpect(jsonPath("$.objectUri").value(
						"s3://media-input/accounts/" + account.accountId() + "/media/" + assetId + "/source"
				));

		mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"mediaAssetId":"%s","operations":[{"type":"METADATA"}]}
								""".formatted(assetId)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.inputUri").value(
						"s3://media-input/accounts/" + account.accountId() + "/media/" + assetId + "/source"
				))
				.andExpect(jsonPath("$.mediaAssetId").value(assetId.toString()));
	}

	@Test
	void completeWithoutPutDoesNotBecomeReady() throws Exception {
		MvcResult created = mockMvc.perform(authed(post("/media-assets"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"filename":"missing.mp4","contentType":"video/mp4","sizeBytes":12}
								"""))
				.andExpect(status().isCreated())
				.andReturn();
		UUID assetId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.mediaAsset.id"));

		mockMvc.perform(authed(post("/media-assets/" + assetId + "/complete")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("UPLOAD_OBJECT_NOT_FOUND"));

		assertThat(jdbcTemplate.queryForObject(
				"select status from media_assets where id = ?",
				String.class,
				assetId
		)).isEqualTo("PENDING_UPLOAD");
	}

	private static String endpoint() {
		return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
	}

	private static void ensureBucket() {
		try (S3Client client = s3Client()) {
			if (client.listBuckets().buckets().stream().noneMatch(bucket -> "media-input".equals(bucket.name()))) {
				client.createBucket(CreateBucketRequest.builder().bucket("media-input").build());
			}
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
}
