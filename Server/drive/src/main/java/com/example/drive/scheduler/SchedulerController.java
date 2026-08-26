package com.example.drive.scheduler;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.scheduler.dto.AssignOperationRequest;
import com.example.drive.scheduler.dto.AssignOperationResponse;
import com.example.drive.scheduler.dto.SchedulerSnapshotResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/scheduler")
public class SchedulerController {

	private final SchedulerService schedulerService;

	public SchedulerController(SchedulerService schedulerService) {
		this.schedulerService = schedulerService;
	}

	@GetMapping("/snapshot")
	public SchedulerSnapshotResponse snapshot() {
		return schedulerService.snapshot();
	}

	@PostMapping("/assign")
	public AssignOperationResponse assign(@Valid @RequestBody AssignOperationRequest request) {
		return schedulerService.assign(request);
	}
}
