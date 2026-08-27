package com.example.drive.security;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentAccount {

	public UUID requireId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof ApiKeyAuthentication apiKeyAuthentication) {
			return apiKeyAuthentication.getAccountId();
		}
		throw new AuthenticationCredentialsNotFoundException("Authentication required");
	}
}
