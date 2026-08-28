package com.example.drive.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

public interface ObjectStoreAccess {

	void verifyObjectExists(String bucket, String key);

	ObjectHead headObject(String bucket, String key);

	PresignedGet presignGet(String bucket, String key, Duration ttl);

	PresignedPut presignPut(String bucket, String key, String contentType, Duration ttl);

	void deleteObject(String bucket, String key);

	record PresignedGet(URI url, Instant expiresAt) {
	}

	record PresignedPut(URI url, Instant expiresAt) {
	}

	record ObjectHead(long contentLength, String contentType) {
	}
}
