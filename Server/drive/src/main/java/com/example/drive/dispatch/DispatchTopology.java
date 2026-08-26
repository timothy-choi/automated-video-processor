package com.example.drive.dispatch;

/**
 * Shared RabbitMQ names. Phase 4A workers declare per-worker queues bound with
 * {@link #workerRoutingKey(String)}. The shared execute queue remains for the
 * legacy Java enqueue path used by tests.
 */
public final class DispatchTopology {

	public static final String EXCHANGE = "media.operations";
	public static final String QUEUE = "media.operations.execute";
	public static final String ROUTING_KEY = "operation.execute";
	public static final String DEAD_LETTER_EXCHANGE = "media.operations.dlx";
	public static final String DEAD_LETTER_QUEUE = "media.operations.execute.dlq";
	public static final String DEAD_LETTER_ROUTING_KEY = "operation.execute.dead";

	private DispatchTopology() {
	}

	public static String workerQueue(String workerId) {
		return "media.worker." + workerId;
	}

	public static String workerRoutingKey(String workerId) {
		return "worker." + workerId;
	}
}
