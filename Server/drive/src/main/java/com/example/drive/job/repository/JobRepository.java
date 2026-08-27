package com.example.drive.job.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.drive.job.domain.Job;

public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

	@Query("select distinct j from Job j left join fetch j.operations where j.id = :id")
	Optional<Job> findByIdWithOperations(@Param("id") UUID id);
}
