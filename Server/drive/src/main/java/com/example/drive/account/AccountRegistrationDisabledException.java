package com.example.drive.account;

public class AccountRegistrationDisabledException extends RuntimeException {

	public AccountRegistrationDisabledException() {
		super("Account registration is disabled");
	}
}
