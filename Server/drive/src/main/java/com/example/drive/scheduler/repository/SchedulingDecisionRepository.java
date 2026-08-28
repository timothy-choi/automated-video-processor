package com.example.drive.scheduler.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.drive.scheduler.domain.SchedulingDecision;

public interface SchedulingDecisionRepository extends JpaRepository<SchedulingDecision, UUID> {

	List<SchedulingDecision> findByOperationIdOrderByCreatedAtAsc(UUID operationId);

	List<SchedulingDecision> findByOperationIdInOrderByCreatedAtAscIdAsc(Collection<UUID> operationIds);

	@Query(value = """
			SELECT sd.worker_id
			FROM scheduling_decisions sd
			JOIN operations o ON o.id = sd.operation_id
			WHERE sd.worker_policy = :workerPolicy
			  AND o.operation_type = :type
			ORDER BY sd.created_at DESC, sd.id DESC
			LIMIT 1
			""", nativeQuery = true)
	Optional<String> findLatestWorker(
			@Param("workerPolicy") String workerPolicy,
			@Param("type") String type
	);
}
