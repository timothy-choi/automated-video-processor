package com.example.drive.account;

import java.util.UUID;

/**
 * Flyway V17 creates this Account and assigns every pre-existing Job to it.
 * Historical Jobs remain reachable by authenticating as this Account (see
 * {@code MEDIA_PLATFORM_BOOTSTRAP_API_KEY}).
 */
public final class LegacyAccounts {

	public static final UUID SYSTEM_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	public static final String SYSTEM_ACCOUNT_NAME = "legacy-system";

	private LegacyAccounts() {
	}
}
