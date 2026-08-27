package com.example.drive.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalWorkerTokensTest {

	private static final String PEPPER = "test-worker-pepper";

	@Test
	void issueAndAuthenticateBindTokenToWorkerId() {
		String token = InternalWorkerTokens.issue(PEPPER, "worker-a");
		assertThat(token).startsWith("mp_wk_worker-a_");
		assertThat(InternalWorkerTokens.authenticate(PEPPER, token)).contains("worker-a");
		assertThat(InternalWorkerTokens.authenticate(PEPPER, token)).isNotEqualTo(
				InternalWorkerTokens.authenticate("other-pepper", token)
		);
	}

	@Test
	void workerATokenDoesNotAuthenticateAsWorkerB() {
		String tokenA = InternalWorkerTokens.issue(PEPPER, "worker-a");
		assertThat(InternalWorkerTokens.authenticate(PEPPER, tokenA)).contains("worker-a");
		assertThat(InternalWorkerTokens.subject(tokenA)).contains("worker-a");
		assertThat(InternalWorkerTokens.subject(tokenA).orElseThrow()).isNotEqualTo("worker-b");
	}

	@Test
	void tamperedMacIsRejected() {
		String token = InternalWorkerTokens.issue(PEPPER, "worker-a");
		String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
		assertThat(InternalWorkerTokens.authenticate(PEPPER, tampered)).isEmpty();
	}

	@Test
	void userApiKeyAndSchedulerTokenAreNotWorkerCredentials() {
		assertThat(InternalWorkerTokens.authenticate(PEPPER, "mp_live_aaaaaaaa")).isEmpty();
		assertThat(InternalWorkerTokens.authenticate(PEPPER, "test-scheduler-token")).isEmpty();
	}

	@Test
	void invalidWorkerIdRejected() {
		assertThatThrownBy(() -> InternalWorkerTokens.issue(PEPPER, "bad worker"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
