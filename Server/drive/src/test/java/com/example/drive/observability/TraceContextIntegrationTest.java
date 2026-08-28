package com.example.drive.observability;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.example.drive.support.AuthenticatedApiTest;
import com.example.drive.support.ControlServiceTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
class TraceContextIntegrationTest extends AuthenticatedApiTest {

	@Test
	void incomingTraceparentIsAcceptedAndReturned() throws Exception {
		String traceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
		String parentSpanId = "bbbbbbbbbbbbbbbb";
		String incoming = "00-" + traceId + "-" + parentSpanId + "-01";
		mockMvc.perform(authed(post("/jobs"))
						.header(TracePropagation.TRACEPARENT, incoming)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://media-input/video.mp4",
								  "operations": [{"type": "METADATA"}]
								}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(header().string(TracePropagation.TRACEPARENT, org.hamcrest.Matchers.containsString(traceId)));

		mockMvc.perform(get("/internal/scheduler/snapshot"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operations[0].traceparent").value(org.hamcrest.Matchers.containsString(traceId)));
	}
}
