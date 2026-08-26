package com.example.drive.dispatch;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

@Service
@ConditionalOnProperty(name = "drive.dispatch.enabled", havingValue = "true")
@ConditionalOnProperty(name = "drive.dispatch.publisher-enabled", havingValue = "true")
public class DispatchPublisher {

	private static final Logger log = LoggerFactory.getLogger(DispatchPublisher.class);

	private final EntityManager entityManager;
	private final DispatchOutboxRepository outboxRepository;
	private final RabbitTemplate rabbitTemplate;
	private final DispatchProperties properties;
	private final Clock clock;

	public DispatchPublisher(
			EntityManager entityManager,
			DispatchOutboxRepository outboxRepository,
			RabbitTemplate rabbitTemplate,
			DispatchProperties properties,
			Clock clock
	) {
		this.entityManager = entityManager;
		this.outboxRepository = outboxRepository;
		this.rabbitTemplate = rabbitTemplate;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public int publishPending() {
		List<UUID> ids = lockPendingIds(properties.getPublishBatchSize());
		int sent = 0;
		Instant now = clock.instant();
		for (UUID id : ids) {
			DispatchOutbox row = outboxRepository.findById(id).orElse(null);
			if (row == null || row.getStatus() != OutboxStatus.PENDING) {
				continue;
			}
			try {
				String routingKey = routingKey(row);
				rabbitTemplate.send(
						DispatchTopology.EXCHANGE,
						routingKey,
						persistentJson(row.getPayloadJson())
				);
				row.markSent(now);
				sent++;
				log.info(
						"published assignment outboxId={} operationId={} routingKey={} workerId={}",
						row.getId(),
						row.getOperationId(),
						routingKey,
						row.getWorkerId()
				);
			}
			catch (RuntimeException ex) {
				row.recordFailedAttempt();
				log.warn(
						"publish failed; outbox remains PENDING outboxId={} operationId={} attempts={} cause={}",
						row.getId(),
						row.getOperationId(),
						row.getPublishAttempts(),
						ex.toString()
				);
			}
		}
		return sent;
	}

	private List<UUID> lockPendingIds(int limit) {
		@SuppressWarnings("unchecked")
		List<Object> rows = entityManager.createNativeQuery("""
				SELECT id
				FROM dispatch_outbox
				WHERE status = 'PENDING'
				ORDER BY created_at ASC
				FOR UPDATE SKIP LOCKED
				LIMIT 
				""" + Math.max(1, limit))
				.getResultList();
		List<UUID> ids = new ArrayList<>(rows.size());
		for (Object row : rows) {
			ids.add(row instanceof UUID uuid ? uuid : UUID.fromString(row.toString()));
		}
		return ids;
	}

	private static String routingKey(DispatchOutbox row) {
		if (row.getRoutingKey() == null || row.getRoutingKey().isBlank()) {
			return DispatchTopology.ROUTING_KEY;
		}
		return row.getRoutingKey();
	}

	private static Message persistentJson(String payload) {
		MessageProperties properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		return new Message(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
	}
}
