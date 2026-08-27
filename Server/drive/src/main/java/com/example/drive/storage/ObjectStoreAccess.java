package com.example.drive.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

public interface ObjectStoreAccess {

	void verifyObjectExists(String bucket, String key);

	PresignedGet presignGet(String bucket, String key, Duration ttl);

	record PresignedGet(URI url, Instant expiresAt) {
	}
}
