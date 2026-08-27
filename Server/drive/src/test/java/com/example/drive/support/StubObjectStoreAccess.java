package com.example.drive.support;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import com.example.drive.storage.ObjectStoreAccess;

public class StubObjectStoreAccess implements ObjectStoreAccess {

	private String lastBucket;
	private String lastKey;
	private Duration lastTtl;
	private RuntimeException verifyError;
	private RuntimeException presignError;
	private URI url = URI.create("http://localhost:9000/media-output/jobs/example/thumbnail.jpg?X-Amz-Signature=test");
	private Instant expiresAt = Instant.parse("2026-08-27T02:15:00Z");

	@Override
	public void verifyObjectExists(String bucket, String key) {
		this.lastBucket = bucket;
		this.lastKey = key;
		if (verifyError != null) {
			throw verifyError;
		}
	}

	@Override
	public PresignedGet presignGet(String bucket, String key, Duration ttl) {
		this.lastBucket = bucket;
		this.lastKey = key;
		this.lastTtl = ttl;
		if (presignError != null) {
			throw presignError;
		}
		return new PresignedGet(url, expiresAt);
	}

	public String lastBucket() {
		return lastBucket;
	}

	public String lastKey() {
		return lastKey;
	}

	public Duration lastTtl() {
		return lastTtl;
	}

	public void failVerify(RuntimeException error) {
		this.verifyError = error;
	}

	public void failPresign(RuntimeException error) {
		this.presignError = error;
	}

	public void reset() {
		lastBucket = null;
		lastKey = null;
		lastTtl = null;
		verifyError = null;
		presignError = null;
	}
}
