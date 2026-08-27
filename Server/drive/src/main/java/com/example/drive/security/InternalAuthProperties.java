package com.example.drive.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drive.internal")
public class InternalAuthProperties {

	/**
	 * Raw scheduler bearer token from {@code SCHEDULER_SERVICE_TOKEN}.
	 * Hashed in memory at startup; never persisted.
	 */
	private String schedulerToken = "";

	/**
	 * HMAC pepper from {@code WORKER_TOKEN_PEPPER}. Used to verify
	 * {@code mp_wk_<workerId>_<hmac>} tokens. Never persisted or given to workers.
	 */
	private String workerTokenPepper = "";

	public String getSchedulerToken() {
		return schedulerToken;
	}

	public void setSchedulerToken(String schedulerToken) {
		this.schedulerToken = schedulerToken == null ? "" : schedulerToken.trim();
	}

	public String getWorkerTokenPepper() {
		return workerTokenPepper;
	}

	public void setWorkerTokenPepper(String workerTokenPepper) {
		this.workerTokenPepper = workerTokenPepper == null ? "" : workerTokenPepper.trim();
	}
}
