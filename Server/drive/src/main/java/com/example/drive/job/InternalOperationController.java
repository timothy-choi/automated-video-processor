package com.example.drive.job;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.job.dto.ClaimedOperationResponse;
import com.example.drive.job.dto.CompleteOperationRequest;
import com.example.drive.job.dto.FailOperationRequest;
import com.example.drive.job.dto.OperationResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/operations")
public class InternalOperationController {

	private final InternalOperationService internalOperationService;

	public InternalOperationController(InternalOperationService internalOperationService) {
		this.internalOperationService = internalOperationService;
	}

	@PostMapping("/claim")
	public ResponseEntity<ClaimedOperationResponse> claim() {
		return internalOperationService.claimNextExecutableOperation()
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping("/{operationId}/complete")
	public OperationResponse complete(
			@PathVariable("operationId") UUID operationId,
			@Valid @RequestBody CompleteOperationRequest request
	) {
		return internalOperationService.complete(operationId, request);
	}

	@PostMapping("/{operationId}/fail")
	public OperationResponse fail(
			@PathVariable("operationId") UUID operationId,
			@Valid @RequestBody FailOperationRequest request
	) {
		return internalOperationService.fail(operationId, request);
	}
}
