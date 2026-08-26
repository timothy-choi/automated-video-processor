package com.example.drive.job;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drive.execution")
public class OperationLeaseProperties {

	private Duration leaseDuration = Duration.ofSeconds(30);

	private Duration leaseSweepInterval = Duration.ofSeconds(5);

	private boolean leaseSweepEnabled = true;

	private int maxAttempts = 3;

	public Duration getLeaseDuration() {
		return leaseDuration;
	}

	public void setLeaseDuration(Duration leaseDuration) {
		if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
			throw new IllegalArgumentException("drive.execution.lease-duration must be positive");
		}
		this.leaseDuration = leaseDuration;
	}

	public Duration getLeaseSweepInterval() {
		return leaseSweepInterval;
	}

	public void setLeaseSweepInterval(Duration leaseSweepInterval) {
		if (leaseSweepInterval == null || leaseSweepInterval.isNegative() || leaseSweepInterval.isZero()) {
			throw new IllegalArgumentException("drive.execution.lease-sweep-interval must be positive");
		}
		this.leaseSweepInterval = leaseSweepInterval;
	}

	public boolean isLeaseSweepEnabled() {
		return leaseSweepEnabled;
	}

	public void setLeaseSweepEnabled(boolean leaseSweepEnabled) {
		this.leaseSweepEnabled = leaseSweepEnabled;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("drive.execution.max-attempts must be at least 1");
		}
		this.maxAttempts = maxAttempts;
	}
}
