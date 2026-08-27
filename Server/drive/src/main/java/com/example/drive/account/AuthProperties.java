package com.example.drive.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drive.auth")
public class AuthProperties {

	/**
	 * Optional raw API key attached to the Flyway {@code legacy-system} Account
	 * at process start. Local/self-hosted bootstrap only. Must match
	 * {@code mp_live_} plus 64 hex characters. Leave empty to skip.
	 */
	private String bootstrapApiKey = "";

	/**
	 * When false, {@code POST /accounts} is rejected. Default is disabled so a
	 * publicly reachable deployment does not allow open registration. Local
	 * development sets {@code ACCOUNT_REGISTRATION_ENABLED=true}.
	 */
	private boolean accountRegistrationEnabled = false;

	public String getBootstrapApiKey() {
		return bootstrapApiKey;
	}

	public void setBootstrapApiKey(String bootstrapApiKey) {
		this.bootstrapApiKey = bootstrapApiKey == null ? "" : bootstrapApiKey.trim();
	}

	public boolean isAccountRegistrationEnabled() {
		return accountRegistrationEnabled;
	}

	public void setAccountRegistrationEnabled(boolean accountRegistrationEnabled) {
		this.accountRegistrationEnabled = accountRegistrationEnabled;
	}
}
