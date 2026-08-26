package com.example.drive.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "drive.execution.lease-sweep-enabled", havingValue = "true", matchIfMissing = true)
public class OperationLeaseSweeper {

	private final InternalOperationService internalOperationService;

	public OperationLeaseSweeper(InternalOperationService internalOperationService) {
		this.internalOperationService = internalOperationService;
	}

	@Scheduled(fixedDelayString = "${drive.execution.lease-sweep-interval:5s}")
	public void sweep() {
		internalOperationService.reclaimExpiredAttempts();
	}
}
