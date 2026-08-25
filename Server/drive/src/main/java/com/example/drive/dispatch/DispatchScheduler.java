package com.example.drive.dispatch;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "drive.dispatch.scheduling-enabled", havingValue = "true")
@ConditionalOnBean(DispatchEnqueueService.class)
public class DispatchScheduler {

	private final DispatchEnqueueService enqueueService;
	private final ObjectProvider<DispatchPublisher> publisher;

	public DispatchScheduler(DispatchEnqueueService enqueueService, ObjectProvider<DispatchPublisher> publisher) {
		this.enqueueService = enqueueService;
		this.publisher = publisher;
	}

	@Scheduled(fixedDelayString = "${drive.dispatch.enqueue-interval-ms:500}")
	public void enqueue() {
		enqueueService.enqueueDispatchableOperations();
	}

	@Scheduled(fixedDelayString = "${drive.dispatch.publish-interval-ms:250}")
	public void publish() {
		DispatchPublisher dispatchPublisher = publisher.getIfAvailable();
		if (dispatchPublisher != null) {
			dispatchPublisher.publishPending();
		}
	}
}
