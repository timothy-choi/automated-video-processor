package com.example.drive.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeySecretsTest {

	@Test
	void generatedKeysAreNamespacedHighEntropySecrets() {
		String key = ApiKeySecrets.generate();
		assertThat(ApiKeySecrets.isWellFormed(key)).isTrue();
		assertThat(key).startsWith("mp_live_");
		assertThat(key).hasSize("mp_live_".length() + 64);
		assertThat(ApiKeySecrets.displayPrefix(key)).hasSize(12);
		assertThat(ApiKeyHasher.sha256Hex(key)).hasSize(64);
		assertThat(ApiKeyHasher.sha256Hex(key)).isNotEqualTo(key);
		assertThat(ApiKeySecrets.generate()).isNotEqualTo(key);
	}
}
