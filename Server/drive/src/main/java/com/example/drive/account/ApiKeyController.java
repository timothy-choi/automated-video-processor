package com.example.drive.account;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.account.dto.ApiKeyCreatedResponse;
import com.example.drive.account.dto.ApiKeyListResponse;
import com.example.drive.account.dto.ApiKeyMetadataResponse;
import com.example.drive.security.CurrentAccount;

@RestController
@RequestMapping("/api-keys")
public class ApiKeyController {

	private final AccountService accountService;
	private final CurrentAccount currentAccount;

	public ApiKeyController(AccountService accountService, CurrentAccount currentAccount) {
		this.accountService = accountService;
		this.currentAccount = currentAccount;
	}

	@PostMapping
	public ResponseEntity<ApiKeyCreatedResponse> createApiKey() {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(accountService.createApiKey(currentAccount.requireId()));
	}

	@GetMapping
	public ApiKeyListResponse listApiKeys() {
		return accountService.listApiKeys(currentAccount.requireId());
	}

	@PostMapping("/{id}/revoke")
	public ApiKeyMetadataResponse revokeApiKey(@PathVariable("id") UUID id) {
		return accountService.revokeApiKey(currentAccount.requireId(), id);
	}
}
