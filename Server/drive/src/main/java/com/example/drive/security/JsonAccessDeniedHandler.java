package com.example.drive.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.example.drive.api.ApiError;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

	private static final Logger log = LoggerFactory.getLogger(JsonAccessDeniedHandler.class);

	private final ObjectMapper objectMapper;
	private final Clock clock;

	public JsonAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Override
	public void handle(
			HttpServletRequest request,
			HttpServletResponse response,
			AccessDeniedException accessDeniedException
	) throws IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof InternalAuthentication internal) {
			log.warn(
					"event=internal_forbidden category=wrong_service serviceType={} subjectId={} path={}",
					internal.principal().serviceType(),
					internal.principal().subjectId(),
					request.getRequestURI()
			);
		}
		else {
			log.warn("event=internal_forbidden category=forbidden path={}", request.getRequestURI());
		}
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(
				response.getOutputStream(),
				new ApiError("FORBIDDEN", "Forbidden", Instant.now(clock))
		);
	}
}
