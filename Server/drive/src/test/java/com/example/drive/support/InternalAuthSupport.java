package com.example.drive.support;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.drive.security.InternalWorkerTokens;

public final class InternalAuthSupport {

	private InternalAuthSupport() {
	}

	public static MockHttpServletRequestBuilder scheduler(MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + InternalAuthTestConstants.SCHEDULER_TOKEN);
	}

	public static MockHttpServletRequestBuilder worker(MockHttpServletRequestBuilder request, String workerId) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + workerToken(workerId));
	}

	public static MockHttpServletRequestBuilder unauthenticated(MockHttpServletRequestBuilder request) {
		return request.header(InternalAuthTestConstants.SKIP_HEADER, "true");
	}

	public static String workerToken(String workerId) {
		return InternalWorkerTokens.issue(InternalAuthTestConstants.WORKER_PEPPER, workerId);
	}

	public static String schedulerToken() {
		return InternalAuthTestConstants.SCHEDULER_TOKEN;
	}
}
