package com.example.drive.observability;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TraceparentResponseFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String incoming = request.getHeader(TracePropagation.TRACEPARENT);
		if (incoming != null && !incoming.isBlank()) {
			response.setHeader(TracePropagation.TRACEPARENT, incoming);
		}
		Scope restored = null;
		String current = TracePropagation.currentTraceparent();
		if (incoming != null && !incoming.isBlank() && !sameTrace(current, incoming)) {
			restored = TracePropagation.restore(incoming, request.getHeader(TracePropagation.TRACESTATE)).makeCurrent();
		}
		try {
			filterChain.doFilter(request, response);
			String traceparent = TracePropagation.currentTraceparent();
			if (traceparent == null) {
				traceparent = incoming;
			}
			if (traceparent != null && !response.isCommitted()) {
				response.setHeader(TracePropagation.TRACEPARENT, traceparent);
			}
		}
		finally {
			if (restored != null) {
				restored.close();
			}
		}
	}

	private static boolean sameTrace(String left, String right) {
		if (left == null || right == null || left.length() < 35 || right.length() < 35) {
			return false;
		}
		return left.regionMatches(true, 3, right, 3, 32);
	}
}
