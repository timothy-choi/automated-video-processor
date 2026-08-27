package com.example.drive.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.account.dto.AccountCreatedResponse;
import com.example.drive.account.dto.CreateAccountRequest;

import jakarta.validation.Valid;

@RestController
public class AccountController {

	private final AccountService accountService;

	public AccountController(AccountService accountService) {
		this.accountService = accountService;
	}

	@PostMapping("/accounts")
	public ResponseEntity<AccountCreatedResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request.name()));
	}
}
