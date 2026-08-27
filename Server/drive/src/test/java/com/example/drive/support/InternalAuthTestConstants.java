package com.example.drive.support;

public final class InternalAuthTestConstants {

	public static final String SCHEDULER_TOKEN = "test-scheduler-token";
	public static final String WORKER_PEPPER = "test-worker-pepper";
	public static final String SCHEDULER_TOKEN_PROPERTY = "drive.internal.scheduler-token=" + SCHEDULER_TOKEN;
	public static final String WORKER_PEPPER_PROPERTY = "drive.internal.worker-token-pepper=" + WORKER_PEPPER;
	public static final String ACCOUNT_REGISTRATION_PROPERTY = "drive.auth.account-registration-enabled=true";
	public static final String SKIP_HEADER = "X-Test-Skip-Internal-Auth";

	private InternalAuthTestConstants() {
	}
}
