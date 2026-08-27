package com.example.drive.job.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.drive.job.domain.Artifact;

public interface ArtifactRepository extends JpaRepository<Artifact, UUID> {

	List<Artifact> findByJobIdOrderByCreatedAtAsc(UUID jobId);

	Optional<Artifact> findByIdAndJobId(UUID id, UUID jobId);

	long countByJobId(UUID jobId);

	boolean existsByOperationId(UUID operationId);

	@Query("""
			select a.jobId as jobId, count(a) as count
			from Artifact a
			where a.jobId in :jobIds
			group by a.jobId
			""")
	List<JobIdCount> countGroupedByJobId(@Param("jobIds") Collection<UUID> jobIds);
}
