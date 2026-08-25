package com.example.drive.job;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.domain.Operation;
import com.example.drive.job.dto.ClaimedOperationResponse;
import com.example.drive.job.dto.CompleteOperationRequest;
import com.example.drive.job.dto.FailOperationRequest;
import com.example.drive.job.dto.MetadataResultDto;
import com.example.drive.job.dto.OperationResponse;
import com.example.drive.job.repository.OperationRepository;

import jakarta.persistence.EntityManager;

@Service
public class InternalOperationService {

	private final EntityManager entityManager;
	private final OperationRepository operationRepository;
	private final Clock clock;

	public InternalOperationService(
			EntityManager entityManager,
			OperationRepository operationRepository,
			Clock clock
	) {
		this.entityManager = entityManager;
		this.operationRepository = operationRepository;
		this.clock = clock;
	}

	@Transactional
	public Optional<ClaimedOperationResponse> claimNextMetadataOperation() {
		Optional<UUID> lockedId = lockNextClaimableMetadataId();
		if (lockedId.isEmpty()) {
			return Optional.empty();
		}

		Instant now = clock.instant();
		Operation operation = operationRepository.findByIdWithJob(lockedId.get())
				.orElseThrow(() -> new OperationNotFoundException(lockedId.get()));
		operation.markRunning(now);
		operation.getJob().markRunningIfQueued(now);
		return Optional.of(ClaimedOperationResponse.from(operation, now));
	}

	@Transactional
	public OperationResponse complete(UUID operationId, CompleteOperationRequest request) {
		Instant now = clock.instant();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		boolean changed = operation.markCompleted(now, request.actualRuntimeMs(), toResultMap(request.result()));
		if (changed) {
			operation.getJob().refreshStatusFromOperations(now);
		}
		return OperationResponse.from(operation);
	}

	@Transactional
	public OperationResponse fail(UUID operationId, FailOperationRequest request) {
		Instant now = clock.instant();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		boolean changed = operation.markFailed(now, request.actualRuntimeMs(), request.reason().trim());
		if (changed) {
			operation.getJob().refreshStatusFromOperations(now);
		}
		return OperationResponse.from(operation);
	}

	private Optional<UUID> lockNextClaimableMetadataId() {
		@SuppressWarnings("unchecked")
		List<Object> rows = entityManager.createNativeQuery("""
				SELECT o.id
				FROM operations o
				JOIN jobs j ON j.id = o.job_id
				WHERE o.status = 'QUEUED'
				  AND o.operation_type = 'METADATA'
				  AND j.input_uri LIKE 'file:%'
				ORDER BY o.created_at ASC, o.operation_order ASC
				FOR UPDATE OF o SKIP LOCKED
				LIMIT 1
				""").getResultList();
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(toUuid(rows.getFirst()));
	}

	private static UUID toUuid(Object value) {
		if (value instanceof UUID uuid) {
			return uuid;
		}
		return UUID.fromString(value.toString());
	}

	private static Map<String, Object> toResultMap(MetadataResultDto result) {
		Map<String, Object> map = new LinkedHashMap<>();
		putIfPresent(map, "durationSeconds", result.durationSeconds());
		putIfPresent(map, "formatName", result.formatName());
		putIfPresent(map, "sizeBytes", result.sizeBytes());
		putIfPresent(map, "videoCodec", result.videoCodec());
		putIfPresent(map, "audioCodec", result.audioCodec());
		putIfPresent(map, "width", result.width());
		putIfPresent(map, "height", result.height());
		putIfPresent(map, "frameRate", result.frameRate());
		return map;
	}

	private static void putIfPresent(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}
}
