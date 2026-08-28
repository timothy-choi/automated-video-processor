package com.example.drive.job.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.drive.job.domain.AttemptStatus;
import com.example.drive.job.domain.ExecutionAttempt;

public interface ExecutionAttemptRepository extends JpaRepository<ExecutionAttempt, UUID> {

	List<ExecutionAttempt> findByOperation_IdOrderByAttemptNumberAsc(UUID operationId);

	@Query("""
			select a from ExecutionAttempt a
			join fetch a.operation o
			where o.job.id = :jobId
			order by a.attemptNumber asc, a.id asc
			""")
	List<ExecutionAttempt> findByJobIdOrderByAttemptNumberAscIdAsc(@Param("jobId") UUID jobId);

	Optional<ExecutionAttempt> findByIdAndOperation_Id(UUID id, UUID operationId);

	@Query("select coalesce(max(a.attemptNumber), 0) from ExecutionAttempt a where a.operation.id = :operationId")
	int maxAttemptNumber(@Param("operationId") UUID operationId);

	@Query("""
			select a.id from ExecutionAttempt a
			where a.status = :running
			  and a.leaseExpiresAt < :cutoff
			""")
	List<UUID> findExpiredRunningIds(
			@Param("running") AttemptStatus running,
			@Param("cutoff") Instant cutoff
	);

	@Query("""
			select a.workerId as workerId, count(a) as runningCount
			from ExecutionAttempt a
			where a.status = :status
			group by a.workerId
			""")
	List<WorkerRunningCount> countRunningByWorker(@Param("status") AttemptStatus status);

	interface WorkerRunningCount {
		String getWorkerId();

		long getRunningCount();
	}
}
