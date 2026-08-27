package com.example.drive.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drive.artifact-access")
public class ArtifactAccessProperties {

	public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
	public static final Duration MIN_TTL = Duration.ofMinutes(1);
	public static final Duration MAX_TTL = Duration.ofHours(24);

	private Duration urlTtl = DEFAULT_TTL;

	public Duration getUrlTtl() {
		return urlTtl;
	}

	public void setUrlTtl(Duration urlTtl) {
		if (urlTtl == null || urlTtl.compareTo(MIN_TTL) < 0 || urlTtl.compareTo(MAX_TTL) > 0) {
			throw new IllegalArgumentException(
					"drive.artifact-access.url-ttl must be between 1 minute and 24 hours"
			);
		}
		this.urlTtl = urlTtl;
	}
}
