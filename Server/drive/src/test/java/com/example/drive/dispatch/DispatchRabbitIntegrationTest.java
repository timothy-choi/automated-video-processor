package com.example.drive.dispatch;

import com.example.drive.support.AuthenticatedApiTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.support.InternalAuthTestConfig;
import com.example.drive.support.PostgresTestcontainersConfig;
import com.example.drive.support.RabbitTestcontainersConfig;
import com.example.drive.support.WorkerTestSupport;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"drive.dispatch.enabled=true",
		"drive.dispatch.scheduling-enabled=false",
		"drive.dispatch.publish-loop-enabled=false",
		"drive.dispatch.publisher-enabled=true",
		"drive.dispatch.http-claim-enabled=false",
		"drive.worker.heartbeat-sweep-enabled=false",
		"drive.execution.lease-sweep-enabled=false",
		"drive.assignment.sweep-enabled=false",
		"drive.internal.scheduler-token=test-scheduler-token",
		"drive.internal.worker-token-pepper=test-worker-pepper",
		"drive.auth.account-registration-enabled=true"
})
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfig.class, RabbitTestcontainersConfig.class, InternalAuthTestConfig.class})
class DispatchRabbitIntegrationTest extends AuthenticatedApiTest {

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
	void clearTablesAndQueue() throws Exception {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
		jdbcTemplate.update("delete from dispatch_outbox");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
		jdbcTemplate.execute("delete from worker_supported_codecs");
		jdbcTemplate.execute("delete from worker_supported_operations");
		jdbcTemplate.execute("delete from workers");
		WorkerTestSupport.register(mockMvc, "worker-a");
		drain(DispatchTopology.QUEUE);
		drain(DispatchTopology.DEAD_LETTER_QUEUE);
	}

	@Test
	void publishesPersistentAssignmentAndStartIsDuplicateSafe() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/sample.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		assertThat(enqueueService.enqueueDispatchableOperations()).isEqualTo(1);
		assertThat(publisher.publishPending()).isEqualTo(1);

		Message message = rabbitTemplate.receive(DispatchTopology.QUEUE, 5000);
		assertThat(message).isNotNull();
		assertThat(message.getMessageProperties().getReceivedDeliveryMode())
				.isEqualTo(MessageDeliveryMode.PERSISTENT);
		String body = new String(message.getBody(), StandardCharsets.UTF_8);
		assertThat(body).contains("schemaVersion").contains("\"type\": \"METADATA\"").contains("s3://media-input/sample.mp4");
		assertThat(body).contains(jobId.toString());

		Integer sent = jdbcTemplate.queryForObject(
				"select count(*) from dispatch_outbox where status = 'SENT'",
				Integer.class
		);
		assertThat(sent).isEqualTo(1);

		UUID operationId = UUID.fromString(JsonPath.read(body, "$.operationId"));
		MvcResult started = mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("STARTED"))
				.andReturn();
		String attemptId = JsonPath.read(started.getResponse().getContentAsString(), "$.attemptId");

		rabbitTemplate.send(DispatchTopology.EXCHANGE, DispatchTopology.ROUTING_KEY, message);
		Message duplicate = rabbitTemplate.receive(DispatchTopology.QUEUE, 5000);
		assertThat(duplicate).isNotNull();
		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ALREADY_RUNNING"));

		mockMvc.perform(post("/internal/operations/" + operationId + "/complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "attemptId": "%s",
								  "actualRuntimeMs": 11,
								  "metadata": {"formatName": "mp4"}
								}
								""".formatted(attemptId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		mockMvc.perform(post("/internal/operations/" + operationId + "/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content(WorkerTestSupport.identityJson("worker-a")))
				.andExpect(jsonPath("$.outcome").value("ALREADY_TERMINAL"));
		mockMvc.perform(authed(get("/jobs/" + jobId)))
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void malformedMessagesCanBeDeadLettered() {
		Message poison = new org.springframework.amqp.core.Message(
				"{not-json".getBytes(StandardCharsets.UTF_8),
				new org.springframework.amqp.core.MessageProperties()
		);
		rabbitTemplate.send(DispatchTopology.EXCHANGE, DispatchTopology.ROUTING_KEY, poison);
		Message received = rabbitTemplate.receive(DispatchTopology.QUEUE, 5000);
		assertThat(received).isNotNull();
		rabbitTemplate.execute(channel -> {
			channel.basicPublish(
					DispatchTopology.DEAD_LETTER_EXCHANGE,
					DispatchTopology.DEAD_LETTER_ROUTING_KEY,
					new com.rabbitmq.client.AMQP.BasicProperties.Builder().deliveryMode(2).build(),
					received.getBody()
			);
			return null;
		});
		Message dead = rabbitTemplate.receive(DispatchTopology.DEAD_LETTER_QUEUE, 5000);
		assertThat(dead).isNotNull();
		assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains("{not-json");
	}

	private UUID createJob(String json) throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}

	private void drain(String queue) {
		for (int i = 0; i < 50; i++) {
			if (rabbitTemplate.receive(queue, 50) == null) {
				return;
			}
		}
	}
}
