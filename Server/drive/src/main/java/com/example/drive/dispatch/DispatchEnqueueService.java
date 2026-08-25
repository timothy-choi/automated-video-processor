package com.example.drive.dispatch;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.OperationNotFoundException;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.repository.OperationRepository;

import jakarta.persistence.EntityManager;

@Service
@ConditionalOnProperty(name = "drive.dispatch.enabled", havingValue = "true")
public class DispatchEnqueueService {

	private static final Logger log = LoggerFactory.getLogger(DispatchEnqueueService.class);

	private final EntityManager entityManager;
	private final OperationRepository operationRepository;
	private final DispatchOutboxRepository outboxRepository;
	private final DispatchProperties properties;
	private final Clock clock;

	public DispatchEnqueueService(
			EntityManager entityManager,
			OperationRepository operationRepository,
			DispatchOutboxRepository outboxRepository,
			DispatchProperties properties,
			Clock clock
	) {
		this.entityManager = entityManager;
		this.operationRepository = operationRepository;
		this.outboxRepository = outboxRepository;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public int enqueueDispatchableOperations() {
		List<UUID> lockedIds = lockNextDispatchableIds(properties.getEnqueueBatchSize());
		if (lockedIds.isEmpty()) {
			return 0;
		}

		Instant now = clock.instant();
		int enqueued = 0;
		for (UUID operationId : lockedIds) {
			Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
					.orElseThrow(() -> new OperationNotFoundException(operationId));
			if (operation.getStatus() != com.example.drive.job.domain.OperationStatus.QUEUED) {
				continue;
			}
			if (outboxRepository.existsByOperationId(operationId)) {
				continue;
			}
			operation.markAssigned(now);
			operation.getJob().refreshStatusFromOperations(now);
			String payload = AssignmentJson.v1(
					operation.getId(),
					operation.getJob().getId(),
					operation.getType().name(),
					operation.getJob().getInputUri(),
					now
			);
			outboxRepository.save(new DispatchOutbox(UUID.randomUUID(), operationId, payload, now));
			enqueued++;
			log.info(
					"enqueued assignment operationId={} jobId={} type={} outbox pending",
					operation.getId(),
					operation.getJob().getId(),
					operation.getType()
			);
		}
		return enqueued;
	}

	private List<UUID> lockNextDispatchableIds(int limit) {
		@SuppressWarnings("unchecked")
		List<Object> rows = entityManager.createNativeQuery("""
				SELECT o.id
				FROM operations o
				JOIN jobs j ON j.id = o.job_id
				WHERE o.status = 'QUEUED'
				  AND o.operation_type IN ('METADATA', 'THUMBNAIL')
				  AND (
				    LOWER(j.input_uri) LIKE 'file:%'
				    OR LOWER(j.input_uri) LIKE 's3:%'
				  )
				ORDER BY o.created_at ASC, o.operation_order ASC
				FOR UPDATE OF o SKIP LOCKED
				LIMIT 
				""" + Math.max(1, limit))
				.getResultList();
		List<UUID> ids = new ArrayList<>(rows.size());
		for (Object row : rows) {
			ids.add(toUuid(row));
		}
		return ids;
	}

	private static UUID toUuid(Object value) {
		if (value instanceof UUID uuid) {
			return uuid;
		}
		return UUID.fromString(value.toString());
	}
}
