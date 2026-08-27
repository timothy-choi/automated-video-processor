package com.example.drive.security;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.AbstractAuthenticationToken;

public class ApiKeyAuthentication extends AbstractAuthenticationToken {

	private final AccountPrincipal principal;

	public ApiKeyAuthentication(AccountPrincipal principal) {
		super(List.of());
		this.principal = principal;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return "";
	}

	@Override
	public AccountPrincipal getPrincipal() {
		return principal;
	}

	public UUID getAccountId() {
		return principal.accountId();
	}

	public UUID getApiKeyId() {
		return principal.apiKeyId();
	}
}
