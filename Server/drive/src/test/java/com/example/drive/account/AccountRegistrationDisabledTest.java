package com.example.drive.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.drive.support.InternalAuthTestConfig;
import com.example.drive.support.PostgresTestcontainersConfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"drive.dispatch.enabled=false",
		"drive.dispatch.scheduling-enabled=false",
		"drive.dispatch.publish-loop-enabled=false",
		"drive.dispatch.publisher-enabled=false",
		"drive.dispatch.http-claim-enabled=false",
		"drive.worker.heartbeat-sweep-enabled=false",
		"drive.execution.lease-sweep-enabled=false",
		"drive.assignment.sweep-enabled=false",
		"drive.internal.scheduler-token=test-scheduler-token",
		"drive.internal.worker-token-pepper=test-worker-pepper",
		"drive.auth.account-registration-enabled=false",
		"spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
})
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, InternalAuthTestConfig.class})
class AccountRegistrationDisabledTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void postAccountsIsForbiddenWhenRegistrationDisabled() throws Exception {
		mockMvc.perform(post("/accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"blocked\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_REGISTRATION_DISABLED"));
	}

	@Test
	void healthRemainsPublicWhenRegistrationDisabled() throws Exception {
		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}
}
