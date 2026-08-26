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
import com.example.drive.job.dto.RenewAttemptResponse;
import com.example.drive.job.dto.StartOperationResponse;
import com.example.drive.job.dto.WorkerIdentityRequest;

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
	public ResponseEntity<ClaimedOperationResponse> claim(@Valid @RequestBody WorkerIdentityRequest request) {
		if (!dispatchProperties.isHttpClaimEnabled()) {
			throw new HttpClaimDisabledException();
		}
		return internalOperationService.claimNextExecutableOperation(request.workerId())
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping("/{operationId}/start")
	public StartOperationResponse start(
			@PathVariable("operationId") UUID operationId,
			@Valid @RequestBody WorkerIdentityRequest request
	) {
		return internalOperationService.start(operationId, request.workerId());
	}

	@PostMapping("/{operationId}/attempts/{attemptId}/renew")
	public RenewAttemptResponse renew(
			@PathVariable("operationId") UUID operationId,
			@PathVariable("attemptId") UUID attemptId,
			@Valid @RequestBody WorkerIdentityRequest request
	) {
		return internalOperationService.renew(operationId, attemptId, request.workerId());
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
