package com.example.drive.security;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.drive.account.AccountService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

	private static final String BEARER_PREFIX = "Bearer ";

	private final AccountService accountService;
	private final JsonAuthenticationEntryPoint authenticationEntryPoint;

	public ApiKeyAuthenticationFilter(
			AccountService accountService,
			JsonAuthenticationEntryPoint authenticationEntryPoint
	) {
		this.accountService = accountService;
		this.authenticationEntryPoint = authenticationEntryPoint;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		if (path.startsWith("/internal/")) {
			return true;
		}
		if ("/health".equals(path) && HttpMethod.GET.matches(request.getMethod())) {
			return true;
		}
		return "/accounts".equals(path) && HttpMethod.POST.matches(request.getMethod());
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		Optional<String> rawKey = extractBearer(request);
		if (rawKey.isEmpty()) {
			authenticationEntryPoint.commence(
					request,
					response,
					new AuthenticationCredentialsNotFoundException("Authentication required")
			);
			return;
		}
		Optional<AccountPrincipal> principal = accountService.authenticate(rawKey.get());
		if (principal.isEmpty()) {
			authenticationEntryPoint.commence(
					request,
					response,
					new AuthenticationCredentialsNotFoundException("Authentication required")
			);
			return;
		}
		ApiKeyAuthentication authentication = new ApiKeyAuthentication(principal.get());
		authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
		SecurityContextHolder.getContext().setAuthentication(authentication);
		log.debug(
				"event=authenticated accountId={} apiKeyId={} path={}",
				principal.get().accountId(),
				principal.get().apiKeyId(),
				request.getRequestURI()
		);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}

	private static Optional<String> extractBearer(HttpServletRequest request) {
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
