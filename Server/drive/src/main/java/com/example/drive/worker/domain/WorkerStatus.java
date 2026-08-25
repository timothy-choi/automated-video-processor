package com.example.drive.worker.domain;

/**
 * Registration status only. {@code REGISTERED} means this worker ID successfully
 * upserted during its latest startup registration. It is not a heartbeat or
 * continuous health signal.
 */
public enum WorkerStatus {
	REGISTERED
}
