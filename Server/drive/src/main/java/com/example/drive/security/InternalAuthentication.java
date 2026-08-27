package com.example.drive.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class InternalAuthentication extends AbstractAuthenticationToken {

	private final InternalPrincipal principal;

	public InternalAuthentication(InternalPrincipal principal) {
		super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.serviceType().name())));
		this.principal = principal;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return "";
	}

	@Override
	public InternalPrincipal getPrincipal() {
		return principal;
	}

	public InternalPrincipal principal() {
		return principal;
	}
}
