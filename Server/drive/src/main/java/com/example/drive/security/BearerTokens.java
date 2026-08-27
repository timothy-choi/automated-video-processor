package com.example.drive.security;

import java.util.Optional;

import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpServletRequest;

public final class BearerTokens {

	private static final String BEARER_PREFIX = "Bearer ";

	private BearerTokens() {
	}

	public static Optional<String> extract(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || header.isBlank()) {
			return Optional.empty();
		}
		if (header.length() <= BEARER_PREFIX.length()
				|| !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return Optional.empty();
		}
		String token = header.substring(BEARER_PREFIX.length()).trim();
		if (token.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(token);
	}
}
