package com.example.drive.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class InternalAuthStartupValidator implements InitializingBean {

	private final InternalAuthProperties properties;

	public InternalAuthStartupValidator(InternalAuthProperties properties) {
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		if (properties.getSchedulerToken().isBlank()) {
			throw new IllegalStateException(
					"SCHEDULER_SERVICE_TOKEN is required (drive.internal.scheduler-token)"
			);
		}
		if (properties.getWorkerTokenPepper().isBlank()) {
			throw new IllegalStateException(
					"WORKER_TOKEN_PEPPER is required (drive.internal.worker-token-pepper)"
			);
		}
	}
}
