package com.example.drive.worker;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.worker.dto.HeartbeatResponse;
import com.example.drive.worker.dto.RegisterWorkerRequest;
import com.example.drive.worker.dto.RegisterWorkerResponse;
import com.example.drive.worker.dto.RegistrationResult;

@RestController
@RequestMapping("/internal/workers")
public class InternalWorkerController {

	private final WorkerService workerService;

	public InternalWorkerController(WorkerService workerService) {
		this.workerService = workerService;
	}

	@PostMapping("/register")
	public ResponseEntity<RegisterWorkerResponse> register(@RequestBody RegisterWorkerRequest request) {
		RegistrationResult result = workerService.register(request);
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(result.worker());
	}

	@PostMapping("/{workerId}/heartbeat")
	public HeartbeatResponse heartbeat(@PathVariable("workerId") String workerId) {
		return workerService.heartbeat(workerId);
	}
}
