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
	 * Runs the enqueue/publish loops on a timer. Disable in tests that invoke
	 * those methods explicitly.
	 */
	private boolean schedulingEnabled = true;

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
