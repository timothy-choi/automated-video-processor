package com.example.drive.worker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "drive.worker")
public class WorkerHeartbeatProperties {

	/**
	 * A worker is AVAILABLE while {@code now - lastHeartbeat} is less than this
	 * timeout. Should be greater than the worker {@code HEARTBEAT_INTERVAL}.
	 */
	private Duration heartbeatTimeout = Duration.ofSeconds(15);

	private Duration heartbeatSweepInterval = Duration.ofSeconds(5);

	private boolean heartbeatSweepEnabled = true;

	public Duration getHeartbeatTimeout() {
		return heartbeatTimeout;
	}

	public void setHeartbeatTimeout(Duration heartbeatTimeout) {
		if (heartbeatTimeout == null || heartbeatTimeout.isNegative() || heartbeatTimeout.isZero()) {
			throw new IllegalArgumentException("drive.worker.heartbeat-timeout must be positive");
		}
		this.heartbeatTimeout = heartbeatTimeout;
	}

	public Duration getHeartbeatSweepInterval() {
		return heartbeatSweepInterval;
	}

	public void setHeartbeatSweepInterval(Duration heartbeatSweepInterval) {
		if (heartbeatSweepInterval == null || heartbeatSweepInterval.isNegative() || heartbeatSweepInterval.isZero()) {
			throw new IllegalArgumentException("drive.worker.heartbeat-sweep-interval must be positive");
		}
		this.heartbeatSweepInterval = heartbeatSweepInterval;
	}

	public boolean isHeartbeatSweepEnabled() {
		return heartbeatSweepEnabled;
	}

	public void setHeartbeatSweepEnabled(boolean heartbeatSweepEnabled) {
		this.heartbeatSweepEnabled = heartbeatSweepEnabled;
	}
}
