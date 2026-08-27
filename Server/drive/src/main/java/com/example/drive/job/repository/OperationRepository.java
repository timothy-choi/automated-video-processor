package com.example.drive.job.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.drive.job.domain.Operation;

public interface OperationRepository extends JpaRepository<Operation, UUID> {

	List<Operation> findByJob_IdOrderByOperationOrderAsc(UUID jobId);

	@Query("""
			select o.job.id as jobId, count(o) as count
			from Operation o
			where o.job.id in :jobIds
			group by o.job.id
			""")
	List<JobIdCount> countGroupedByJobId(@Param("jobIds") Collection<UUID> jobIds);

	@Query("select o from Operation o join fetch o.job where o.id = :id")
	Optional<Operation> findByIdWithJob(@Param("id") UUID id);

	@Query("select o from Operation o join fetch o.job j left join fetch j.operations where o.id = :id")
	Optional<Operation> findByIdWithJobAndOperations(@Param("id") UUID id);

	@Query("""
			select o from Operation o
			join fetch o.job j
			where o.status = com.example.drive.job.domain.OperationStatus.QUEUED
			  and o.type in (
			    com.example.drive.job.domain.OperationType.METADATA,
			    com.example.drive.job.domain.OperationType.THUMBNAIL,
			    com.example.drive.job.domain.OperationType.AUDIO_EXTRACTION,
			    com.example.drive.job.domain.OperationType.TRANSCODE_1080P,
			    com.example.drive.job.domain.OperationType.H264_TO_AV1
			  )
			  and (
			    lower(j.inputUri) like 'file:%'
			    or lower(j.inputUri) like 's3:%'
			  )
			order by o.queuedAt asc, o.operationOrder asc, o.id asc
			""")
	List<Operation> findSchedulableQueued();

	@Query("""
			select o.id from Operation o
			where o.status = com.example.drive.job.domain.OperationStatus.ASSIGNED
			  and o.currentAttemptId is null
			  and o.assignedAt is not null
			  and o.assignedAt < :cutoff
			""")
	List<UUID> findExpiredUnstartedAssignedIds(@Param("cutoff") Instant cutoff);
}
