package com.example.drive.job;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.domain.Artifact;
import com.example.drive.job.domain.ArtifactType;
import com.example.drive.job.domain.Operation;
import com.example.drive.job.dto.ArtifactCompletionDto;
import com.example.drive.job.dto.ClaimedOperationResponse;
import com.example.drive.job.dto.CompleteOperationRequest;
import com.example.drive.job.dto.FailOperationRequest;
import com.example.drive.job.dto.MetadataResultDto;
import com.example.drive.job.dto.OperationResponse;
import com.example.drive.job.dto.StartOperationResponse;
import com.example.drive.job.dto.StartOutcome;
import com.example.drive.job.repository.ArtifactRepository;
import com.example.drive.job.repository.OperationRepository;

import jakarta.persistence.EntityManager;

@Service
public class InternalOperationService {

	private final EntityManager entityManager;
	private final OperationRepository operationRepository;
	private final ArtifactRepository artifactRepository;
	private final Clock clock;

	public InternalOperationService(
			EntityManager entityManager,
			OperationRepository operationRepository,
			ArtifactRepository artifactRepository,
			Clock clock
	) {
		this.entityManager = entityManager;
		this.operationRepository = operationRepository;
		this.artifactRepository = artifactRepository;
		this.clock = clock;
	}

	@Transactional
	public StartOperationResponse start(UUID operationId) {
		lockJobForOperation(operationId);
		Instant now = clock.instant();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		return switch (operation.getStatus()) {
			case ASSIGNED -> {
				operation.markRunning(now);
				operation.getJob().refreshStatusFromOperations(now);
				yield StartOperationResponse.from(StartOutcome.STARTED, operation);
			}
			case RUNNING -> StartOperationResponse.from(StartOutcome.ALREADY_RUNNING, operation);
			case COMPLETED, FAILED, CANCELLED -> StartOperationResponse.from(StartOutcome.ALREADY_TERMINAL, operation);
			case QUEUED -> StartOperationResponse.from(StartOutcome.INVALID_STATE, operation);
		};
	}

	@Transactional
	public Optional<ClaimedOperationResponse> claimNextExecutableOperation() {
		Optional<UUID> lockedId = lockNextClaimableId();
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
		lockJobForOperation(operationId);
		Instant now = clock.instant();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));

		boolean changed = switch (operation.getType()) {
			case METADATA -> completeMetadata(operation, request, now);
			case THUMBNAIL -> completeThumbnail(operation, request, now);
			default -> throw new InvalidJobRequestException(
					"UNSUPPORTED_COMPLETION_TYPE",
					"Internal completion in this phase supports METADATA and THUMBNAIL only"
			);
		};
		if (changed) {
			operation.getJob().refreshStatusFromOperations(now);
		}
		return OperationResponse.from(operation);
	}

	@Transactional
	public OperationResponse fail(UUID operationId, FailOperationRequest request) {
		lockJobForOperation(operationId);
		Instant now = clock.instant();
		Operation operation = operationRepository.findByIdWithJobAndOperations(operationId)
				.orElseThrow(() -> new OperationNotFoundException(operationId));
		boolean changed = operation.markFailed(now, request.actualRuntimeMs(), request.reason().trim());
		if (changed) {
			operation.getJob().refreshStatusFromOperations(now);
		}
		return OperationResponse.from(operation);
	}

	private boolean completeMetadata(Operation operation, CompleteOperationRequest request, Instant now) {
		if (request.metadata() == null) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "metadata is required for METADATA completion");
		}
		if (request.artifact() != null) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "artifact is not allowed for METADATA completion");
		}
		return operation.markCompleted(now, request.actualRuntimeMs(), toResultMap(request.metadata()));
	}

	private boolean completeThumbnail(Operation operation, CompleteOperationRequest request, Instant now) {
		if (request.artifact() == null) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "artifact is required for THUMBNAIL completion");
		}
		if (request.metadata() != null) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "metadata is not allowed for THUMBNAIL completion");
		}
		ArtifactCompletionDto artifact = request.artifact();
		validateThumbnailObjectUri(artifact.objectUri());
		boolean changed = operation.markCompleted(now, request.actualRuntimeMs(), null);
		if (changed && !artifactRepository.existsByOperationId(operation.getId())) {
			artifactRepository.save(new Artifact(
					UUID.randomUUID(),
					operation.getJob().getId(),
					operation.getId(),
					ArtifactType.THUMBNAIL,
					artifact.objectUri().trim(),
					artifact.contentType().trim(),
					artifact.sizeBytes(),
					artifact.checksum().trim(),
					now
			));
		}
		return changed;
	}

	private void lockJobForOperation(UUID operationId) {
		@SuppressWarnings("unchecked")
		List<Object> rows = entityManager.createNativeQuery("""
				SELECT j.id
				FROM jobs j
				JOIN operations o ON o.job_id = j.id
				WHERE o.id = :id
				FOR UPDATE OF j
				""")
				.setParameter("id", operationId)
				.getResultList();
		if (rows.isEmpty()) {
			throw new OperationNotFoundException(operationId);
		}
	}

	private Optional<UUID> lockNextClaimableId() {
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
				LIMIT 1
				""").getResultList();
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(toUuid(rows.getFirst()));
	}

	private static void validateThumbnailObjectUri(String raw) {
		URI uri;
		try {
			uri = URI.create(raw.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "artifact objectUri is not a valid URI");
		}
		if (!"s3".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "artifact objectUri must be s3://bucket/key");
		}
		String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
		if (path.isBlank()) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "artifact objectUri must include an object key");
		}
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
