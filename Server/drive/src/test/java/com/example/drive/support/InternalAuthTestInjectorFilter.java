package com.example.drive.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.drive.security.InternalWorkerTokens;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Test-only convenience: existing business-logic tests omit internal
 * Authorization headers. This filter infers the matching test credential so
 * those tests keep covering scheduling/execution behavior. Dedicated auth
 * tests set headers themselves or send {@link InternalAuthTestConstants#SKIP_HEADER}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalAuthTestInjectorFilter extends OncePerRequestFilter {

	private static final Pattern WORKER_ID_JSON = Pattern.compile("\"workerId\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern ATTEMPT_ID_JSON = Pattern.compile("\"attemptId\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern HEARTBEAT_PATH = Pattern.compile("^/internal/workers/([^/]+)/heartbeat$");

	private final JdbcTemplate jdbcTemplate;

	public InternalAuthTestInjectorFilter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		if (!request.getRequestURI().startsWith("/internal/")
				|| request.getHeader(HttpHeaders.AUTHORIZATION) != null
				|| "true".equalsIgnoreCase(request.getHeader(InternalAuthTestConstants.SKIP_HEADER))) {
			filterChain.doFilter(request, response);
			return;
		}
		byte[] body = request.getInputStream().readAllBytes();
		String token = inferToken(request.getRequestURI(), new String(body, StandardCharsets.UTF_8));
		HttpServletRequest wrapped = new CachedBodyRequest(request, body);
		if (token != null) {
			wrapped = new AuthorizationRequest(wrapped, "Bearer " + token);
		}
		filterChain.doFilter(wrapped, response);
	}

	private String inferToken(String path, String body) {
		if (path.startsWith("/internal/scheduler")) {
			return InternalAuthTestConstants.SCHEDULER_TOKEN;
		}
		Matcher heartbeat = HEARTBEAT_PATH.matcher(path);
		if (heartbeat.matches()) {
			return workerToken(heartbeat.group(1));
		}
		String workerId = jsonField(WORKER_ID_JSON, body);
		if (workerId != null) {
			return workerToken(workerId);
		}
		if (path.contains("/complete") || path.contains("/fail")) {
			String attemptId = jsonField(ATTEMPT_ID_JSON, body);
			String owner = lookupAttemptWorker(attemptId);
			return workerToken(owner != null ? owner : "worker-a");
		}
		return workerToken("worker-a");
	}

	private String lookupAttemptWorker(String attemptId) {
		if (attemptId == null || jdbcTemplate == null) {
			return null;
		}
		try {
			UUID id = UUID.fromString(attemptId);
			List<String> rows = jdbcTemplate.query(
					"select worker_id from execution_attempts where id = ?",
					(rs, rowNum) -> rs.getString(1),
					id
			);
			return rows.isEmpty() ? null : rows.getFirst();
		}
		catch (RuntimeException ignored) {
			return null;
		}
	}

	private static String workerToken(String workerId) {
		try {
			return InternalWorkerTokens.issue(InternalAuthTestConstants.WORKER_PEPPER, workerId);
		}
		catch (IllegalArgumentException ex) {
			return InternalWorkerTokens.issue(InternalAuthTestConstants.WORKER_PEPPER, "worker-a");
		}
	}

	private static String jsonField(Pattern pattern, String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		Matcher matcher = pattern.matcher(body);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		private CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream input = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public int read() {
					return input.read();
				}

				@Override
				public boolean isFinished() {
					return input.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener readListener) {
					throw new UnsupportedOperationException();
				}
			};
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}
	}

	private static final class AuthorizationRequest extends HttpServletRequestWrapper {

		private final String authorization;

		private AuthorizationRequest(HttpServletRequest request, String authorization) {
			super(request);
			this.authorization = authorization;
		}

		@Override
		public String getHeader(String name) {
			if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
				return authorization;
			}
			return super.getHeader(name);
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
				return Collections.enumeration(List.of(authorization));
			}
			return super.getHeaders(name);
		}

		@Override
		public Enumeration<String> getHeaderNames() {
			List<String> names = Collections.list(super.getHeaderNames());
			if (names.stream().noneMatch(HttpHeaders.AUTHORIZATION::equalsIgnoreCase)) {
				names.add(HttpHeaders.AUTHORIZATION);
			}
			return Collections.enumeration(names);
		}
	}
}
