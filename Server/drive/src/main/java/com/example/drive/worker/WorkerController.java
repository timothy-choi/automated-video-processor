package com.example.drive.worker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.worker.dto.WorkerResponse;
import com.example.drive.worker.dto.WorkersResponse;

@RestController
@RequestMapping("/workers")
public class WorkerController {

	private final WorkerService workerService;

	public WorkerController(WorkerService workerService) {
		this.workerService = workerService;
	}

	@GetMapping
	public WorkersResponse listWorkers() {
		return workerService.listWorkers();
	}

	@GetMapping("/{id}")
	public WorkerResponse getWorker(@PathVariable("id") String id) {
		return workerService.getWorker(id);
	}
}
