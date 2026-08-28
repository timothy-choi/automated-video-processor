package com.example.drive.support;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.drive.storage.ObjectNotFoundException;
import com.example.drive.storage.ObjectStoreAccess;

public class StubObjectStoreAccess implements ObjectStoreAccess {

	private String lastBucket;
	private String lastKey;
	private Duration lastTtl;
	private String lastContentType;
	private RuntimeException verifyError;
	private RuntimeException presignError;
	private URI url = URI.create("http://localhost:9000/media-output/jobs/example/thumbnail.jpg?X-Amz-Signature=test");
	private URI putUrl = URI.create(
			"https://127.0.0.1:9443/media-input/accounts/example/media/example/source?X-Amz-Signature=test"
	);
	private Instant expiresAt = Instant.parse("2026-08-27T02:15:00Z");
	private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
	private boolean missingUnlessStored;

	@Override
	public void verifyObjectExists(String bucket, String key) {
		headObject(bucket, key);
	}

	@Override
	public ObjectHead headObject(String bucket, String key) {
		this.lastBucket = bucket;
		this.lastKey = key;
		if (verifyError != null) {
			throw verifyError;
		}
		StoredObject stored = objects.get(objectId(bucket, key));
		if (stored != null) {
			return new ObjectHead(stored.sizeBytes(), stored.contentType());
		}
		if (missingUnlessStored) {
			throw new ObjectNotFoundException(bucket, key);
		}
		return new ObjectHead(1L, "application/octet-stream");
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

	@Override
	public PresignedPut presignPut(String bucket, String key, String contentType, Duration ttl) {
		this.lastBucket = bucket;
		this.lastKey = key;
		this.lastContentType = contentType;
		this.lastTtl = ttl;
		if (presignError != null) {
			throw presignError;
		}
		return new PresignedPut(putUrl, expiresAt);
	}

	@Override
	public void deleteObject(String bucket, String key) {
		this.lastBucket = bucket;
		this.lastKey = key;
		objects.remove(objectId(bucket, key));
	}

	public void put(String bucket, String key, long sizeBytes, String contentType) {
		objects.put(objectId(bucket, key), new StoredObject(sizeBytes, contentType));
	}

	public void requireStoredObjects() {
		this.missingUnlessStored = true;
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

	public String lastContentType() {
		return lastContentType;
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
		lastContentType = null;
		verifyError = null;
		presignError = null;
		objects.clear();
		missingUnlessStored = false;
	}

	private static String objectId(String bucket, String key) {
		return bucket + "/" + key;
	}

	private record StoredObject(long sizeBytes, String contentType) {
	}
}
