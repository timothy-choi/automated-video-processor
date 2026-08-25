package com.example.drive.job;

public class HttpClaimDisabledException extends RuntimeException {

	public HttpClaimDisabledException() {
		super("HTTP claim is disabled; workers should consume RabbitMQ assignments");
	}
}
