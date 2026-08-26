package com.example.drive.job;

public class WorkerNotEligibleException extends RuntimeException {

	private final String code;

	public WorkerNotEligibleException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
