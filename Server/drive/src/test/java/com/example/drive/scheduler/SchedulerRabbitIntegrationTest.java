package com.example.drive.scheduler;

import com.example.drive.support.AuthenticatedApiTest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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

import com.example.drive.dispatch.DispatchPublisher;
import com.example.drive.dispatch.DispatchTopology;
import com.example.drive.support.InternalAuthTestConfig;
import com.example.drive.support.PostgresTestcontainersConfig;
import com.example.drive.support.RabbitTestcontainersConfig;
import com.example.drive.support.WorkerTestSupport;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class SchedulerRabbitIntegrationTest extends AuthenticatedApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SchedulerService schedulerService;

	@Autowired
	private DispatchPublisher publisher;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@BeforeEach
	void clearTablesAndQueues() throws Exception {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from execution_attempts");
		jdbcTemplate.update("delete from scheduling_decisions");
		jdbcTemplate.update("delete from dispatch_outbox");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
		jdbcTemplate.execute("delete from worker_supported_codecs");
		jdbcTemplate.execute("delete from worker_supported_operations");
		jdbcTemplate.execute("delete from workers");
		WorkerTestSupport.register(mockMvc, "worker-a");
		WorkerTestSupport.register(mockMvc, "worker-b", "METADATA");
		declareWorkerQueue("worker-a");
		declareWorkerQueue("worker-b");
		drain(DispatchTopology.workerQueue("worker-a"));
		drain(DispatchTopology.workerQueue("worker-b"));
		drain(DispatchTopology.QUEUE);
	}

	@Test
	void targetedPublishReachesSelectedWorkerQueueOnly() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/sample.mp4",
				  "operations": [{"type": "THUMBNAIL"}]
				}
				""");
		UUID operationId = UUID.fromString(jdbcTemplate.queryForObject(
				"select id from operations where job_id = ?",
				String.class,
				jobId
		));
		schedulerService.assign(new com.example.drive.scheduler.dto.AssignOperationRequest(
				operationId,
				"worker-a",
				"FIFO",
				"LEXICOGRAPHIC"
		));
		assertThat(publisher.publishPending()).isEqualTo(1);

		Message forA = rabbitTemplate.receive(DispatchTopology.workerQueue("worker-a"), 5000);
		assertThat(forA).isNotNull();
		assertThat(forA.getMessageProperties().getReceivedDeliveryMode())
				.isEqualTo(MessageDeliveryMode.PERSISTENT);
		String body = new String(forA.getBody(), StandardCharsets.UTF_8);
		assertThat(body).contains("schemaVersion").contains("worker-a").contains("FIFO");
		assertThat(body).contains("LEXICOGRAPHIC").contains("workerPolicy");
		assertThat(body).contains(operationId.toString());
		assertThat(body).contains("assignmentId");
		assertThat(body).doesNotContain("attemptId");

		Message forB = rabbitTemplate.receive(DispatchTopology.workerQueue("worker-b"), 500);
		assertThat(forB).isNull();
		Message shared = rabbitTemplate.receive(DispatchTopology.QUEUE, 200);
		assertThat(shared).isNull();
	}

	private UUID createJob(String json) throws Exception {
		MvcResult result = mockMvc.perform(authed(post("/jobs"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}

	private void declareWorkerQueue(String workerId) {
		rabbitTemplate.execute(channel -> {
			channel.exchangeDeclare(DispatchTopology.EXCHANGE, "direct", true);
			Map<String, Object> args = new HashMap<>();
			args.put("x-dead-letter-exchange", DispatchTopology.DEAD_LETTER_EXCHANGE);
			args.put("x-dead-letter-routing-key", DispatchTopology.DEAD_LETTER_ROUTING_KEY);
			channel.queueDeclare(
					DispatchTopology.workerQueue(workerId),
					true,
					false,
					false,
					args
			);
			channel.queueBind(
					DispatchTopology.workerQueue(workerId),
					DispatchTopology.EXCHANGE,
					DispatchTopology.workerRoutingKey(workerId)
			);
			return null;
		});
	}

	private void drain(String queue) {
		for (int i = 0; i < 50; i++) {
			if (rabbitTemplate.receive(queue, 50) == null) {
				return;
			}
		}
	}
}
