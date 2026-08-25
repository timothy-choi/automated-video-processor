package com.example.drive.job;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.dispatch.DispatchProperties;
import com.example.drive.job.dto.ClaimedOperationResponse;
import com.example.drive.job.dto.CompleteOperationRequest;
import com.example.drive.job.dto.FailOperationRequest;
import com.example.drive.job.dto.OperationResponse;
import com.example.drive.job.dto.StartOperationResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/operations")
public class InternalOperationController {

	private final InternalOperationService internalOperationService;
	private final DispatchProperties dispatchProperties;

	public InternalOperationController(
			InternalOperationService internalOperationService,
			DispatchProperties dispatchProperties
	) {
		this.internalOperationService = internalOperationService;
		this.dispatchProperties = dispatchProperties;
	}

	@PostMapping("/claim")
	public ResponseEntity<ClaimedOperationResponse> claim() {
		if (!dispatchProperties.isHttpClaimEnabled()) {
			throw new HttpClaimDisabledException();
		}
		return internalOperationService.claimNextExecutableOperation()
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping("/{operationId}/start")
	public StartOperationResponse start(@PathVariable("operationId") UUID operationId) {
		return internalOperationService.start(operationId);
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
