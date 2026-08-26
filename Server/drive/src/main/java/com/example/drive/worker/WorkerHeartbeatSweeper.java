package com.example.drive.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "drive.worker.heartbeat-sweep-enabled", havingValue = "true", matchIfMissing = true)
public class WorkerHeartbeatSweeper {

	private final WorkerService workerService;

	public WorkerHeartbeatSweeper(WorkerService workerService) {
		this.workerService = workerService;
	}

	@Scheduled(fixedDelayString = "${drive.worker.heartbeat-sweep-interval:5s}")
	public void sweep() {
		workerService.markStaleWorkers();
	}
}
