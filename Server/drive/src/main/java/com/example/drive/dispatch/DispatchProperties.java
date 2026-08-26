package com.example.drive.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drive.dispatch")
public class DispatchProperties {

	/**
	 * Selects QUEUED METADATA/THUMBNAIL operations and writes outbox rows.
	 * Isolated from {@code POST /jobs}.
	 */
	private boolean enabled = true;

	/**
	 * Legacy Java operation-selection loop. Off by default in Phase 4A; the Go
	 * scheduler owns placement. Tests may still call
	 * {@link DispatchEnqueueService} directly.
	 */
	private boolean schedulingEnabled = false;

	/**
	 * Runs the outbox publish loop. Keep this on in production so targeted
	 * assignments still leave PostgreSQL through the transactional outbox.
	 * Disable in tests that invoke {@link DispatchPublisher#publishPending()}
	 * explicitly.
	 */
	private boolean publishLoopEnabled = true;

	/**
	 * Temporary Phase 2 HTTP claim. Off by default so it does not compete with
	 * RabbitMQ dispatch. Existing claim tests turn this back on.
	 */
	private boolean httpClaimEnabled = false;

	private long enqueueIntervalMs = 500;

	private long publishIntervalMs = 250;

	private int enqueueBatchSize = 20;

	private int publishBatchSize = 20;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isSchedulingEnabled() {
		return schedulingEnabled;
	}

	public void setSchedulingEnabled(boolean schedulingEnabled) {
		this.schedulingEnabled = schedulingEnabled;
	}

	public boolean isPublishLoopEnabled() {
		return publishLoopEnabled;
	}

	public void setPublishLoopEnabled(boolean publishLoopEnabled) {
		this.publishLoopEnabled = publishLoopEnabled;
	}

	public boolean isHttpClaimEnabled() {
		return httpClaimEnabled;
	}

	public void setHttpClaimEnabled(boolean httpClaimEnabled) {
		this.httpClaimEnabled = httpClaimEnabled;
	}

	public long getEnqueueIntervalMs() {
		return enqueueIntervalMs;
	}

	public void setEnqueueIntervalMs(long enqueueIntervalMs) {
		this.enqueueIntervalMs = enqueueIntervalMs;
	}

	public long getPublishIntervalMs() {
		return publishIntervalMs;
	}

	public void setPublishIntervalMs(long publishIntervalMs) {
		this.publishIntervalMs = publishIntervalMs;
	}

	public int getEnqueueBatchSize() {
		return enqueueBatchSize;
	}

	public void setEnqueueBatchSize(int enqueueBatchSize) {
		this.enqueueBatchSize = enqueueBatchSize;
	}

	public int getPublishBatchSize() {
		return publishBatchSize;
	}

	public void setPublishBatchSize(int publishBatchSize) {
		this.publishBatchSize = publishBatchSize;
	}
}
