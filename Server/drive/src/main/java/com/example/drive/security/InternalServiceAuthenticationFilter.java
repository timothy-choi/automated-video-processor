package com.example.drive.security;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class InternalServiceAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(InternalServiceAuthenticationFilter.class);

	private final InternalServiceAuthenticator authenticator;
	private final JsonAuthenticationEntryPoint authenticationEntryPoint;

	public InternalServiceAuthenticationFilter(
			InternalServiceAuthenticator authenticator,
			JsonAuthenticationEntryPoint authenticationEntryPoint
	) {
		this.authenticator = authenticator;
		this.authenticationEntryPoint = authenticationEntryPoint;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/internal/");
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		Optional<String> rawToken = BearerTokens.extract(request);
		if (rawToken.isEmpty()) {
			log.warn(
					"event=internal_auth_failed category=missing_credential path={}",
					request.getRequestURI()
			);
			authenticationEntryPoint.commence(
					request,
					response,
					new AuthenticationCredentialsNotFoundException("Authentication required")
			);
			return;
		}
		Optional<InternalPrincipal> principal = authenticator.authenticate(rawToken.get());
		if (principal.isEmpty()) {
			log.warn(
					"event=internal_auth_failed category=invalid_credential path={}",
					request.getRequestURI()
			);
			authenticationEntryPoint.commence(
					request,
					response,
					new AuthenticationCredentialsNotFoundException("Authentication required")
			);
			return;
		}
		InternalAuthentication authentication = new InternalAuthentication(principal.get());
		authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
		SecurityContextHolder.getContext().setAuthentication(authentication);
		log.debug(
				"event=internal_authenticated serviceType={} subjectId={} path={}",
				principal.get().serviceType(),
				principal.get().subjectId(),
				request.getRequestURI()
		);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}
}
