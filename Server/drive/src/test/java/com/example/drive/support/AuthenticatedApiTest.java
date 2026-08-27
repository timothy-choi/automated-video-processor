package com.example.drive.support;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

public abstract class AuthenticatedApiTest {

	@Autowired
	protected MockMvc mockMvc;

	protected AuthTestSupport.TestAccount account;

	@BeforeEach
	void authenticateApi() throws Exception {
		account = AuthTestSupport.createAccount(mockMvc, "test-" + UUID.randomUUID());
	}

	protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
		return authed(request, account);
	}

	protected MockHttpServletRequestBuilder authed(
			MockHttpServletRequestBuilder request,
			AuthTestSupport.TestAccount credentials
	) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.rawKey());
	}
}
