package com.example.drive.observability;

public final class TelemetryNoise {

	private TelemetryNoise() {
	}

	public static boolean isNoisyPath(String path) {
		if (path == null || path.isBlank()) {
			return false;
		}
		if ("/health".equals(path) || path.startsWith("/actuator")) {
			return true;
		}
		if (path.equals("/internal/scheduler/snapshot") || path.endsWith("/scheduler/snapshot")) {
			return true;
		}
		if (path.contains("/heartbeat")) {
			return true;
		}
		return path.endsWith("/renew") || path.contains("/attempts/") && path.endsWith("/renew");
	}
}
