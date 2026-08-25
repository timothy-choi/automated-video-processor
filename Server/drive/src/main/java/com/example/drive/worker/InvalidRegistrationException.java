package com.example.drive.worker;

public class InvalidRegistrationException extends RuntimeException {

	private final String code;

	public InvalidRegistrationException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
