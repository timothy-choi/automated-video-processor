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

import com.example.drive.observability.LogCorrelation;
import com.example.drive.observability.MediaAttributes;
import com.example.drive.observability.MediaSpans;
import com.example.drive.observability.TracePropagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

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
	private final Tracer tracer;

	public DispatchPublisher(
			EntityManager entityManager,
			DispatchOutboxRepository outboxRepository,
			RabbitTemplate rabbitTemplate,
			DispatchProperties properties,
			Clock clock,
			Tracer tracer
	) {
		this.entityManager = entityManager;
		this.outboxRepository = outboxRepository;
		this.rabbitTemplate = rabbitTemplate;
		this.properties = properties;
		this.clock = clock;
		this.tracer = tracer;
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
				publish(row, routingKey);
				row.markSent(now);
				sent++;
				try (LogCorrelation correlation = LogCorrelation.open(null, row.getOperationId(), null, row.getWorkerId())) {
					log.info(
							"published assignment outboxId={} operationId={} routingKey={} workerId={}",
							row.getId(),
							row.getOperationId(),
							routingKey,
							row.getWorkerId()
					);
				}
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

	private void publish(DispatchOutbox row, String routingKey) {
		Context parent = TracePropagation.restore(row.getTraceparent(), row.getTracestate());
		Span span = tracer.spanBuilder(MediaSpans.RABBITMQ_PUBLISH)
				.setParent(parent)
				.setSpanKind(SpanKind.PRODUCER)
				.startSpan();
		try (Scope ignored = span.makeCurrent()) {
			MediaSpans.set(span, MediaAttributes.OPERATION_ID, row.getOperationId().toString());
			if (row.getWorkerId() != null) {
				MediaSpans.set(span, MediaAttributes.WORKER_ID, row.getWorkerId());
			}
			MessageProperties properties = new MessageProperties();
			properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
			properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			TracePropagation.injectAmqp(properties, Context.current());
			Message message = new Message(row.getPayloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
			rabbitTemplate.send(DispatchTopology.EXCHANGE, routingKey, message);
		}
		catch (RuntimeException ex) {
			span.setStatus(StatusCode.ERROR, ex.getClass().getSimpleName());
			throw ex;
		}
		finally {
			span.end();
		}
	}
}
