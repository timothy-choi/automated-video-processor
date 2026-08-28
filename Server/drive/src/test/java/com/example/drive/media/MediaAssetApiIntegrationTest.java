package com.example.drive.media;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.support.AuthTestSupport;
import com.example.drive.support.AuthenticatedApiTest;
import com.example.drive.support.ControlServiceTest;
import com.example.drive.support.StubObjectStoreAccess;
import com.example.drive.support.StubObjectStoreAccessConfig;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
@Import(StubObjectStoreAccessConfig.class)
class MediaAssetApiIntegrationTest extends AuthenticatedApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StubObjectStoreAccess objectStoreAccess;

	@BeforeEach
	void resetStore() {
		objectStoreAccess.reset();
		objectStoreAccess.requireStoredObjects();
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
		jdbcTemplate.update("delete from scheduling_decisions");
		jdbcTemplate.update("delete from dispatch_outbox");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
		jdbcTemplate.update("delete from media_assets");
	}

	@Test
	void createReturnsPendingUploadWithAccountScopedKeyAndDoesNotPersistUrl() throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/media-assets"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "filename": "../../clips/sample.mp4",
								  "contentType": "video/mp4",
								  "sizeBytes": 12345678
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.mediaAsset.status").value("PENDING_UPLOAD"))
				.andExpect(jsonPath("$.mediaAsset.filename").value("sample.mp4"))
				.andExpect(jsonPath("$.mediaAsset.contentType").value("video/mp4"))
				.andExpect(jsonPath("$.mediaAsset.sizeBytes").value(12345678))
				.andExpect(jsonPath("$.upload.method").value("PUT"))
				.andExpect(jsonPath("$.upload.url").isString())
				.andExpect(jsonPath("$.upload.headers['Content-Type']").value("video/mp4"))
				.andExpect(jsonPath("$.upload.secretKey").doesNotExist())
				.andReturn();

		UUID assetId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.mediaAsset.id"));
		String url = JsonPath.read(result.getResponse().getContentAsString(), "$.upload.url");
		assertThat(url).contains("X-Amz-Signature");
		assertThat(url).contains("127.0.0.1");
		assertThat(url).doesNotContain("minio.internal");

		String objectKey = jdbcTemplate.queryForObject(
				"select object_key from media_assets where id = ?",
				String.class,
				assetId
		);
		assertThat(objectKey).isEqualTo("accounts/" + account.accountId() + "/media/" + assetId + "/source");
		Integer urlColumns = jdbcTemplate.queryForObject(
				"""
						select count(*) from information_schema.columns
						where table_name = 'media_assets' and column_name in ('url', 'upload_url', 'presigned_url')
						""",
				Integer.class
		);
		assertThat(urlColumns).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"select status from media_assets where id = ?",
				String.class,
				assetId
		)).isEqualTo("PENDING_UPLOAD");
		assertThat(objectStoreAccess.lastBucket()).isEqualTo("media-input");
		assertThat(objectStoreAccess.lastKey()).isEqualTo(objectKey);
		assertThat(objectStoreAccess.lastTtl()).isEqualTo(Duration.ofMinutes(15));
		assertThat(objectStoreAccess.lastContentType()).isEqualTo("video/mp4");
	}

	@Test
	void declaredOversizeIsRejectedBeforePresign() throws Exception {
		mockMvc.perform(authed(post("/media-assets"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "filename": "huge.mp4",
								  "contentType": "video/mp4",
								  "sizeBytes": 2147483649
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));
		assertThat(jdbcTemplate.queryForObject("select count(*) from media_assets", Integer.class)).isZero();
		assertThat(objectStoreAccess.lastKey()).isNull();
	}

	@Test
	void invalidContentTypeIsRejected() throws Exception {
		mockMvc.perform(authed(post("/media-assets"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "filename": "notes.txt",
								  "contentType": "text/plain",
								  "sizeBytes": 12
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CONTENT_TYPE"));
	}

	@Test
	void ownerCanListAndGetWithoutPresignedUrl() throws Exception {
		UUID assetId = createAsset("clip.mp4");
		mockMvc.perform(authed(get("/media-assets/" + assetId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(assetId.toString()))
				.andExpect(jsonPath("$.filename").value("clip.mp4"))
				.andExpect(jsonPath("$.objectUri").value(
						"s3://media-input/accounts/" + account.accountId() + "/media/" + assetId + "/source"
				))
				.andExpect(jsonPath("$.url").doesNotExist());

		mockMvc.perform(authed(get("/media-assets")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(assetId.toString()))
				.andExpect(jsonPath("$.items[0].objectUri").doesNotExist())
				.andExpect(jsonPath("$.items[0].url").doesNotExist());
	}

	@Test
	void otherAccountCannotReadOrMutateAndCannotCreateJob() throws Exception {
		UUID assetId = createAsset("secret.mp4");
		AuthTestSupport.TestAccount other = AuthTestSupport.createAccount(mockMvc, "other-media");

		mockMvc.perform(authed(get("/media-assets/" + assetId), other))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEDIA_ASSET_NOT_FOUND"));
		mockMvc.perform(authed(post("/media-assets/" + assetId + "/upload-url"), other))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEDIA_ASSET_NOT_FOUND"));
		mockMvc.perform(authed(post("/media-assets/" + assetId + "/complete"), other))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEDIA_ASSET_NOT_FOUND"));
		mockMvc.perform(authed(delete("/media-assets/" + assetId), other))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEDIA_ASSET_NOT_FOUND"));
		mockMvc.perform(authed(post("/jobs"), other)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"mediaAssetId":"%s","operations":[{"type":"METADATA"}]}
								""".formatted(assetId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEDIA_ASSET_NOT_FOUND"));
	}

	@Test
	void completeWithoutObjectStaysPending() throws Exception {
		UUID assetId = createAsset("missing.mp4");
		mockMvc.perform(authed(post("/media-assets/" + assetId + "/complete")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("UPLOAD_OBJECT_NOT_FOUND"));
		assertThat(jdbcTemplate.queryForObject(
				"select status from media_assets where id = ?",
				String.class,
				assetId
		)).isEqualTo("PENDING_UPLOAD");
	}

	@Test
	void completeAfterStoredObjectBecomesReadyWithActualSize() throws Exception {
		UUID assetId = createAsset("ready.mp4");
		String key = "accounts/" + account.accountId() + "/media/" + assetId + "/source";
		objectStoreAccess.put("media-input", key, 42L, "video/mp4");

		mockMvc.perform(authed(post("/media-assets/" + assetId + "/complete")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.sizeBytes").value(42));

		mockMvc.perform(authed(post("/media-assets/" + assetId + "/complete")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.sizeBytes").value(42));

		mockMvc.perform(authed(post("/media-assets/" + assetId + "/upload-url")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEDIA_ASSET_ALREADY_READY"));

		String objectKey = jdbcTemplate.queryForObject(
				"select object_key from media_assets where id = ?",
				String.class,
				assetId
		);
		assertThat(objectKey).isEqualTo(key);
	}

	@Test
	void pendingAssetCannotCreateJob() throws Exception {
		UUID assetId = createAsset("pending.mp4");
		mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"mediaAssetId":"%s","operations":[{"type":"METADATA"}]}
								""".formatted(assetId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEDIA_ASSET_NOT_READY"));
	}

	@Test
	void readyAssetCreatesJobWithCanonicalUriAndProvenance() throws Exception {
		UUID assetId = createAsset("job.mp4");
		String key = "accounts/" + account.accountId() + "/media/" + assetId + "/source";
		objectStoreAccess.put("media-input", key, 99L, "video/mp4");
		mockMvc.perform(authed(post("/media-assets/" + assetId + "/complete")))
				.andExpect(status().isOk());

		MvcResult result = mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"mediaAssetId":"%s","operations":[{"type":"METADATA"}]}
								""".formatted(assetId)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.inputUri").value("s3://media-input/" + key))
				.andExpect(jsonPath("$.mediaAssetId").value(assetId.toString()))
				.andReturn();
		UUID jobId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
		UUID storedAssetId = jdbcTemplate.queryForObject(
				"select media_asset_id from jobs where id = ?",
				UUID.class,
				jobId
		);
		assertThat(storedAssetId).isEqualTo(assetId);

		mockMvc.perform(authed(delete("/media-assets/" + assetId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEDIA_ASSET_IN_USE"));
	}

	@Test
	void bothSourcesRejectedAndInputUriStillWorks() throws Exception {
		UUID assetId = createAsset("both.mp4");
		mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "mediaAssetId": "%s",
								  "inputUri": "s3://media-input/legacy.mp4",
								  "operations": [{"type": "METADATA"}]
								}
								""".formatted(assetId)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("JOB_SOURCE_CONFLICT"));

		mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://media-input/legacy.mp4",
								  "operations": [{"type": "METADATA"}]
								}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.inputUri").value("s3://media-input/legacy.mp4"))
				.andExpect(jsonPath("$.mediaAssetId").doesNotExist());
	}

	@Test
	void unusedPendingAssetCanBeDeleted() throws Exception {
		UUID assetId = createAsset("abandon.mp4");
		mockMvc.perform(authed(delete("/media-assets/" + assetId)))
				.andExpect(status().isNoContent());
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from media_assets where id = ?",
				Integer.class,
				assetId
		)).isZero();
	}

	private UUID createAsset(String filename) throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/media-assets"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"filename":"%s","contentType":"video/mp4","sizeBytes":1024}
								""".formatted(filename)))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.mediaAsset.id"));
	}
}
