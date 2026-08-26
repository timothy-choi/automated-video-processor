package com.example.drive.worker.domain;

/**
 * Control-plane liveness, not OS-process death and not job ownership.
 * {@code AVAILABLE} means a registration or heartbeat was observed within the
 * configured timeout. {@code UNAVAILABLE} means the latest heartbeat is missing
 * or older than that timeout (including {@code lastHeartbeat == null}).
 */
public enum WorkerStatus {
	AVAILABLE,
	UNAVAILABLE
}
