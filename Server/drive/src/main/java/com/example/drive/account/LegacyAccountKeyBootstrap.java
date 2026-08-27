package com.example.drive.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Attaches a known API key to the Flyway {@code legacy-system} Account when
 * {@code MEDIA_PLATFORM_BOOTSTRAP_API_KEY} is set. This is a self-hosted
 * development bootstrap, not production identity administration.
 */
@Component
public class LegacyAccountKeyBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LegacyAccountKeyBootstrap.class);

	private final AuthProperties authProperties;
	private final AccountService accountService;

	public LegacyAccountKeyBootstrap(AuthProperties authProperties, AccountService accountService) {
		this.authProperties = authProperties;
		this.accountService = accountService;
	}

	@Override
	public void run(ApplicationArguments args) {
		String rawKey = authProperties.getBootstrapApiKey();
		if (rawKey.isBlank()) {
			return;
		}
		if (!ApiKeySecrets.isWellFormed(rawKey)) {
			throw new IllegalStateException(
					"MEDIA_PLATFORM_BOOTSTRAP_API_KEY must match mp_live_ followed by 64 hex characters"
			);
		}
		accountService.ensureBootstrapKey(LegacyAccounts.SYSTEM_ACCOUNT_ID, rawKey);
		log.info("event=legacy_account_bootstrap_ready accountId={}", LegacyAccounts.SYSTEM_ACCOUNT_ID);
	}
}
