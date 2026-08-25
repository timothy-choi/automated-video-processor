package com.example.drive.dispatch;

/**
 * Shared RabbitMQ names. Go workers declare the same topology.
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
}
