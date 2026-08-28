package com.example.drive.media;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drive.media-upload")
public class MediaUploadProperties {

	public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;
	public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
	public static final Duration MIN_TTL = Duration.ofMinutes(1);
	public static final Duration MAX_TTL = Duration.ofHours(24);
	public static final String DEFAULT_INPUT_BUCKET = "media-input";

	private long maxBytes = DEFAULT_MAX_BYTES;
	private Duration urlTtl = DEFAULT_TTL;
	private String inputBucket = DEFAULT_INPUT_BUCKET;

	public long getMaxBytes() {
		return maxBytes;
	}

	public void setMaxBytes(long maxBytes) {
		if (maxBytes < 1) {
			throw new IllegalArgumentException("drive.media-upload.max-bytes must be >= 1");
		}
		this.maxBytes = maxBytes;
	}

	public Duration getUrlTtl() {
		return urlTtl;
	}

	public void setUrlTtl(Duration urlTtl) {
		if (urlTtl == null || urlTtl.compareTo(MIN_TTL) < 0 || urlTtl.compareTo(MAX_TTL) > 0) {
			throw new IllegalArgumentException(
					"drive.media-upload.url-ttl must be between 1 minute and 24 hours"
			);
		}
		this.urlTtl = urlTtl;
	}

	public String getInputBucket() {
		return inputBucket;
	}

	public void setInputBucket(String inputBucket) {
		if (inputBucket == null || inputBucket.isBlank()) {
			throw new IllegalArgumentException("drive.media-upload.input-bucket must not be blank");
		}
		this.inputBucket = inputBucket.trim();
	}
}
