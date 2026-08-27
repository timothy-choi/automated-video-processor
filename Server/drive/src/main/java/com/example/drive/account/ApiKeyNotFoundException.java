package com.example.drive.account;

import java.util.UUID;

public class ApiKeyNotFoundException extends RuntimeException {

	private final UUID apiKeyId;

	public ApiKeyNotFoundException(UUID apiKeyId) {
		super("API key " + apiKeyId + " was not found");
		this.apiKeyId = apiKeyId;
	}

	public UUID getApiKeyId() {
		return apiKeyId;
	}
}
