package com.example.drive.support;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class WorkerTestSupport {

	private WorkerTestSupport() {
	}

	public static String identityJson(String workerId) {
		return "{\"workerId\":\"" + workerId + "\"}";
	}

	public static String startJson(String workerId, UUID assignmentId) {
		if (assignmentId == null) {
			return identityJson(workerId);
		}
		return "{\"workerId\":\"" + workerId + "\",\"assignmentId\":\"" + assignmentId + "\"}";
	}

	public static String registrationJson(String workerId) {
		return registrationJson(workerId, "METADATA", "THUMBNAIL");
	}

	public static String registrationJson(String workerId, String... operations) {
		String joined = String.join("\",\"", operations);
		return """
				{
				  "workerId": "%s",
				  "hostname": "mac-%s",
				  "supportedOperations": ["%s"],
				  "supportedCodecs": ["h264"],
				  "cpuArchitecture": "arm64",
				  "cpuCores": 8,
				  "memoryBytes": 17179869184,
				  "ffmpegVersion": "7.1"
				}
				""".formatted(workerId, workerId, joined);
	}

	public static void register(MockMvc mockMvc, String workerId) throws Exception {
		register(mockMvc, workerId, "METADATA", "THUMBNAIL");
	}

	public static void register(MockMvc mockMvc, String workerId, String... operations) throws Exception {
		mockMvc.perform(post("/internal/workers/register")
						.header(org.springframework.http.HttpHeaders.AUTHORIZATION,
								"Bearer " + InternalAuthSupport.workerToken(workerId))
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson(workerId, operations)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("AVAILABLE"));
	}
}
