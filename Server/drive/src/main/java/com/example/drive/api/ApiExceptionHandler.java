package com.example.drive.api;

import java.time.Clock;
import java.time.Instant;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.drive.job.HttpClaimDisabledException;
import com.example.drive.job.IllegalOperationStateException;
import com.example.drive.job.InvalidJobRequestException;
import com.example.drive.job.JobNotFoundException;
import com.example.drive.job.OperationNotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

	private final Clock clock;

	public ApiExceptionHandler(Clock clock) {
		this.clock = clock;
	}

	@ExceptionHandler(JobNotFoundException.class)
	public ResponseEntity<ApiError> handleJobNotFound(JobNotFoundException ex) {
		return respond(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", ex.getMessage());
	}

	@ExceptionHandler(OperationNotFoundException.class)
	public ResponseEntity<ApiError> handleOperationNotFound(OperationNotFoundException ex) {
		return respond(HttpStatus.NOT_FOUND, "OPERATION_NOT_FOUND", ex.getMessage());
	}

	@ExceptionHandler(HttpClaimDisabledException.class)
	public ResponseEntity<ApiError> handleHttpClaimDisabled(HttpClaimDisabledException ex) {
		return respond(HttpStatus.NOT_FOUND, "CLAIM_DISABLED", ex.getMessage());
	}

	@ExceptionHandler(IllegalOperationStateException.class)
	public ResponseEntity<ApiError> handleIllegalOperationState(IllegalOperationStateException ex) {
		return respond(HttpStatus.CONFLICT, "INVALID_OPERATION_STATE", ex.getMessage());
	}

	@ExceptionHandler(InvalidJobRequestException.class)
	public ResponseEntity<ApiError> handleInvalidJobRequest(InvalidJobRequestException ex) {
		return respond(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(this::formatFieldError)
				.collect(Collectors.joining("; "));
		if (message.isBlank()) {
			message = "Request validation failed";
		}
		return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleUnreadableMessage(HttpMessageNotReadableException ex) {
		String detail = ex.getMostSpecificCause().getMessage();
		if (detail != null && detail.toLowerCase(java.util.Locale.ROOT).contains("enum")) {
			return respond(HttpStatus.BAD_REQUEST, "INVALID_ENUM_VALUE", "Request contains an unknown enum value");
		}
		return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is invalid JSON");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid value for parameter '" + ex.getName() + "'");
	}

	private String formatFieldError(FieldError error) {
		return error.getField() + ": " + error.getDefaultMessage();
	}

	private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ApiError(code, message, Instant.now(clock)));
	}
}
