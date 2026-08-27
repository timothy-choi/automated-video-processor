package com.example.drive.storage;

import java.net.URI;
import java.util.Locale;

import com.example.drive.job.ArtifactUriInvalidException;

/**
 * Canonical Artifact identity parser. Matches worker {@code ParseS3URI}:
 * {@code s3://bucket/key} with a non-empty bucket and key.
 */
public final class S3ObjectLocator {

	private final String bucket;
	private final String key;

	private S3ObjectLocator(String bucket, String key) {
		this.bucket = bucket;
		this.key = key;
	}

	public static S3ObjectLocator parse(String objectUri) {
		if (objectUri == null || objectUri.isBlank()) {
			throw new ArtifactUriInvalidException("Artifact object URI is missing");
		}
		URI uri;
		try {
			uri = URI.create(objectUri.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new ArtifactUriInvalidException("Artifact object URI is not a valid URI");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"s3".equals(scheme)) {
			throw new ArtifactUriInvalidException("Artifact object URI must use the s3:// scheme");
		}
		String bucket = uri.getHost();
		if (bucket == null || bucket.isBlank()) {
			throw new ArtifactUriInvalidException("Artifact object URI is missing a bucket");
		}
		String path = uri.getPath() == null ? "" : uri.getPath();
		String key = path.startsWith("/") ? path.substring(1) : path;
		if (key.isBlank()) {
			throw new ArtifactUriInvalidException("Artifact object URI is missing an object key");
		}
		return new S3ObjectLocator(bucket, key);
	}

	public String bucket() {
		return bucket;
	}

	public String key() {
		return key;
	}

	public String fileName() {
		int slash = key.lastIndexOf('/');
		return slash < 0 ? key : key.substring(slash + 1);
	}

	public String canonicalUri() {
		return "s3://" + bucket + "/" + key;
	}
}
