package com.example.drive.job;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drive.assignment")
public class OperationAssignmentProperties {

	/**
	 * How long an Operation may stay ASSIGNED with no ExecutionAttempt before
	 * it is eligible for recovery. Distinct from lease duration, which applies
	 * only after start.
	 */
	private Duration startTimeout = Duration.ofSeconds(15);

	private Duration sweepInterval = Duration.ofSeconds(5);

	private boolean sweepEnabled = true;

	public Duration getStartTimeout() {
		return startTimeout;
	}

	public void setStartTimeout(Duration startTimeout) {
		if (startTimeout == null || startTimeout.isNegative() || startTimeout.isZero()) {
			throw new IllegalArgumentException("drive.assignment.start-timeout must be positive");
		}
		this.startTimeout = startTimeout;
	}

	public Duration getSweepInterval() {
		return sweepInterval;
	}

	public void setSweepInterval(Duration sweepInterval) {
		if (sweepInterval == null || sweepInterval.isNegative() || sweepInterval.isZero()) {
			throw new IllegalArgumentException("drive.assignment.sweep-interval must be positive");
		}
		this.sweepInterval = sweepInterval;
	}

	public boolean isSweepEnabled() {
		return sweepEnabled;
	}

	public void setSweepEnabled(boolean sweepEnabled) {
		this.sweepEnabled = sweepEnabled;
	}
}
