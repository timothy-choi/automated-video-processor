package com.example.drive.support;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class AuthTestSupport {

	private AuthTestSupport() {
	}

	public record TestAccount(UUID accountId, UUID apiKeyId, String rawKey, String prefix) {
	}

	public static TestAccount createAccount(MockMvc mockMvc, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"" + name + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.account.id").isString())
				.andExpect(jsonPath("$.apiKey.key").isString())
				.andReturn();
		String body = result.getResponse().getContentAsString();
		return new TestAccount(
				UUID.fromString(JsonPath.read(body, "$.account.id")),
				UUID.fromString(JsonPath.read(body, "$.apiKey.id")),
				JsonPath.read(body, "$.apiKey.key"),
				JsonPath.read(body, "$.apiKey.prefix")
		);
	}
}
