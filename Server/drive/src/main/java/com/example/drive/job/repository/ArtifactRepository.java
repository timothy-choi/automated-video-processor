package com.example.drive.job.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.drive.job.domain.Artifact;

public interface ArtifactRepository extends JpaRepository<Artifact, UUID> {

	List<Artifact> findByJobIdOrderByCreatedAtAsc(UUID jobId);

	boolean existsByOperationId(UUID operationId);
}
