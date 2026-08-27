package com.example.drive.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drive.object-store")
public class ObjectStoreProperties {

	private String endpoint = "http://localhost:9000";

	/**
	 * Client-reachable endpoint used when signing download URLs.
	 * Blank means the same value as {@code endpoint}. Workers and Java HEAD
	 * traffic keep using {@code endpoint}; never rewrite a signed URL after the fact.
	 */
	private String publicEndpoint = "";

	private String region = "us-east-1";

	private String accessKey = "minioadmin";

	private String secretKey = "minioadmin";

	private boolean forcePathStyle = true;

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			throw new IllegalArgumentException("drive.object-store.endpoint must not be blank");
		}
		this.endpoint = endpoint.trim();
	}

	public String getPublicEndpoint() {
		return publicEndpoint;
	}

	public void setPublicEndpoint(String publicEndpoint) {
		this.publicEndpoint = publicEndpoint == null ? "" : publicEndpoint.trim();
	}

	public String getPresignEndpoint() {
		return publicEndpoint.isBlank() ? endpoint : publicEndpoint;
	}

	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = (region == null || region.isBlank()) ? "us-east-1" : region.trim();
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		if (accessKey == null || accessKey.isBlank()) {
			throw new IllegalArgumentException("drive.object-store.access-key must not be blank");
		}
		this.accessKey = accessKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		if (secretKey == null || secretKey.isBlank()) {
			throw new IllegalArgumentException("drive.object-store.secret-key must not be blank");
		}
		this.secretKey = secretKey;
	}

	public boolean isForcePathStyle() {
		return forcePathStyle;
	}

	public void setForcePathStyle(boolean forcePathStyle) {
		this.forcePathStyle = forcePathStyle;
	}
}
