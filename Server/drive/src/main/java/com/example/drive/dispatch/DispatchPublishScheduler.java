package com.example.drive.dispatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes pending outbox rows. Independent of Java operation selection so
 * the Go scheduler can be the placement authority while this loop still
 * drains the transactional outbox.
 */
@Component
@ConditionalOnProperty(name = "drive.dispatch.publish-loop-enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(DispatchPublisher.class)
public class DispatchPublishScheduler {

	private final DispatchPublisher publisher;

	public DispatchPublishScheduler(DispatchPublisher publisher) {
		this.publisher = publisher;
	}

	@Scheduled(fixedDelayString = "${drive.dispatch.publish-interval-ms:250}")
	public void publish() {
		publisher.publishPending();
	}
}
