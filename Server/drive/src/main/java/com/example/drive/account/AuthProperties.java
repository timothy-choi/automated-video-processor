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

	public String getBootstrapApiKey() {
		return bootstrapApiKey;
	}

	public void setBootstrapApiKey(String bootstrapApiKey) {
		this.bootstrapApiKey = bootstrapApiKey == null ? "" : bootstrapApiKey.trim();
	}
}
