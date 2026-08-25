package com.example.drive.job;

public class InvalidJobRequestException extends RuntimeException {

	private final String code;

	public InvalidJobRequestException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
