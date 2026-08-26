package com.example.drive.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "drive.assignment.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class AssignmentRecoverySweeper {

	private final AssignmentRecoveryService assignmentRecoveryService;

	public AssignmentRecoverySweeper(AssignmentRecoveryService assignmentRecoveryService) {
		this.assignmentRecoveryService = assignmentRecoveryService;
	}

	@Scheduled(fixedDelayString = "${drive.assignment.sweep-interval:5s}")
	public void sweep() {
		assignmentRecoveryService.reclaimUnstartedAssignments();
	}
}
