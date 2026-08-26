package com.example.drive.dispatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Legacy Java operation-selection loop. Off by default in Phase 4A so it does
 * not compete with the Go scheduler. Tests and explicit
 * {@link DispatchEnqueueService#enqueueDispatchableOperations()} calls still
 * use the enqueue service directly.
 */
@Component
@ConditionalOnProperty(name = "drive.dispatch.scheduling-enabled", havingValue = "true")
@ConditionalOnBean(DispatchEnqueueService.class)
public class DispatchScheduler {

	private final DispatchEnqueueService enqueueService;

	public DispatchScheduler(DispatchEnqueueService enqueueService) {
		this.enqueueService = enqueueService;
	}

	@Scheduled(fixedDelayString = "${drive.dispatch.enqueue-interval-ms:500}")
	public void enqueue() {
		enqueueService.enqueueDispatchableOperations();
	}
}
