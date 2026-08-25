package com.example.drive.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.drive.support.MockRabbitTemplateConfig;
import com.example.drive.support.PostgresTestcontainersConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"drive.dispatch.enabled=true",
		"drive.dispatch.scheduling-enabled=false",
		"drive.dispatch.publisher-enabled=true",
		"drive.dispatch.http-claim-enabled=false",
		"spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
})
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, MockRabbitTemplateConfig.class})
class DispatchPublisherIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DispatchEnqueueService enqueueService;

	@Autowired
	private DispatchPublisher publisher;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@BeforeEach
	void clearTables() {
		reset(rabbitTemplate);
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from dispatch_outbox");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
	}

	@Test
	void failedPublishLeavesOutboxPendingAndRetryable() throws Exception {
		createMetadataJob();
		assertThat(enqueueService.enqueueDispatchableOperations()).isEqualTo(1);
		doThrow(new AmqpException("broker down")).when(rabbitTemplate).send(anyString(), anyString(), any());

		assertThat(publisher.publishPending()).isZero();

		Integer pending = jdbcTemplate.queryForObject(
				"select count(*) from dispatch_outbox where status = 'PENDING' and publish_attempts = 1",
				Integer.class
		);
		assertThat(pending).isEqualTo(1);

		doNothing().when(rabbitTemplate).send(anyString(), anyString(), any());
		assertThat(publisher.publishPending()).isEqualTo(1);
		Integer sent = jdbcTemplate.queryForObject(
				"select count(*) from dispatch_outbox where status = 'SENT' and sent_at is not null",
				Integer.class
		);
		assertThat(sent).isEqualTo(1);
	}

	@Test
	void duplicatePublishDoesNotChangeAssignedState() throws Exception {
		createMetadataJob();
		enqueueService.enqueueDispatchableOperations();
		doNothing().when(rabbitTemplate).send(anyString(), anyString(), any());

		assertThat(publisher.publishPending()).isEqualTo(1);
		assertThat(publisher.publishPending()).isZero();
		verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any());

		Integer assigned = jdbcTemplate.queryForObject(
				"select count(*) from operations where status = 'ASSIGNED'",
				Integer.class
		);
		assertThat(assigned).isEqualTo(1);
	}

	private void createMetadataJob() throws Exception {
		mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://media-input/video.mp4",
								  "operations": [{"type": "METADATA"}]
								}
								"""))
				.andExpect(status().isAccepted());
	}
}
