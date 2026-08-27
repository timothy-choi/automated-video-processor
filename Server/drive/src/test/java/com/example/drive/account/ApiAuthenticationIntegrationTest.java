package com.example.drive.account;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.drive.support.AuthTestSupport;
import com.example.drive.support.AuthenticatedApiTest;
import com.example.drive.support.ControlServiceTest;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
class ApiAuthenticationIntegrationTest extends AuthenticatedApiTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void missingAuthorizationReturns401() throws Exception {
		mockMvc.perform(get("/jobs"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.message").value("Authentication required"));
	}

	@Test
	void malformedAuthorizationReturnsSame401() throws Exception {
		mockMvc.perform(get("/jobs").header("Authorization", "Bearer"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.message").value("Authentication required"));

		mockMvc.perform(get("/jobs").header("Authorization", "Basic abc"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.message").value("Authentication required"));

		mockMvc.perform(get("/jobs").header("Authorization", "Bearer not-a-real-key"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.message").value("Authentication required"));
	}

	@Test
	void unknownKeyReturnsSame401() throws Exception {
		mockMvc.perform(get("/jobs").header(
						"Authorization",
						"Bearer mp_live_ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
				))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.message").value("Authentication required"));
	}

	@Test
	void validKeyCanListJobs() throws Exception {
		mockMvc.perform(authed(get("/jobs")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isArray())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void revokedKeyReturns401() throws Exception {
		mockMvc.perform(authed(post("/api-keys/" + account.apiKeyId() + "/revoke")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(account.apiKeyId().toString()))
				.andExpect(jsonPath("$.revokedAt").isString())
				.andExpect(jsonPath("$.key").doesNotExist());

		mockMvc.perform(get("/jobs").header("Authorization", "Bearer " + account.rawKey()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void healthStaysPublicAndInternalRequiresServiceAuth() throws Exception {
		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));

		mockMvc.perform(com.example.drive.support.InternalAuthSupport.unauthenticated(
						post("/internal/workers/register"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "workerId": "auth-public-worker",
								  "hostname": "mac-auth",
								  "supportedOperations": ["METADATA"],
								  "supportedCodecs": ["h264"],
								  "cpuArchitecture": "arm64",
								  "cpuCores": 2,
								  "memoryBytes": 1024,
								  "ffmpegVersion": "7.1"
								}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void rawApiKeyIsNotPersistedAndHashLookupWorks() throws Exception {
		Integer hashMatches = jdbcTemplate.queryForObject(
				"select count(*) from api_keys where id = ? and key_hash = ?",
				Integer.class,
				account.apiKeyId(),
				ApiKeyHasher.sha256Hex(account.rawKey())
		);
		assertThat(hashMatches).isEqualTo(1);

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"select key_prefix, key_hash from api_keys where id = ?",
				account.apiKeyId()
		);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("key_prefix")).isEqualTo(account.prefix());
		assertThat(String.valueOf(rows.get(0).get("key_hash"))).isNotEqualTo(account.rawKey());
		assertThat(account.rawKey()).startsWith("mp_live_");
		assertThat(account.rawKey()).hasSize("mp_live_".length() + 64);

		Integer rawInAnyColumn = jdbcTemplate.queryForObject(
				"""
						select count(*) from api_keys
						where id = ?
						  and (
						    key_prefix = ?
						    or key_hash = ?
						    or cast(id as text) = ?
						  )
						""",
				Integer.class,
				account.apiKeyId(),
				account.rawKey(),
				account.rawKey(),
				account.rawKey()
		);
		assertThat(rawInAnyColumn).isZero();
	}

	@Test
	void additionalKeyIsShownOnceThenListedAsMetadata() throws Exception {
		String created = mockMvc.perform(authed(post("/api-keys")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.key").isString())
				.andExpect(jsonPath("$.prefix").isString())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String secondKey = JsonPath.read(created, "$.key");
		String secondId = JsonPath.read(created, "$.id");

		mockMvc.perform(authed(get("/api-keys")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].key").doesNotExist())
				.andExpect(jsonPath("$.items[1].key").doesNotExist())
				.andExpect(jsonPath("$.items[1].id").value(secondId));

		mockMvc.perform(get("/jobs").header("Authorization", "Bearer " + secondKey))
				.andExpect(status().isOk());
	}

	@Test
	void revokeDoesNotRevealOtherAccountsKeys() throws Exception {
		AuthTestSupport.TestAccount other = AuthTestSupport.createAccount(mockMvc, "other-keys");
		mockMvc.perform(authed(post("/api-keys/" + other.apiKeyId() + "/revoke")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("API_KEY_NOT_FOUND"));
	}

	@Test
	void duplicateKeyHashIsRejectedByUniqueConstraint() {
		String hash = "b".repeat(64);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		jdbcTemplate.update("""
						insert into api_keys (id, account_id, key_prefix, key_hash, created_at)
						values (?, ?, 'mp_live_bbbb', ?, now())
						""",
				first,
				account.accountId(),
				hash
		);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
						insert into api_keys (id, account_id, key_prefix, key_hash, created_at)
						values (?, ?, 'mp_live_cccc', ?, now())
						""",
				second,
				account.accountId(),
				hash
		)).hasMessageContaining("api_keys_key_hash_unique");
	}
}
