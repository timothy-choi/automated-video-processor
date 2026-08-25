package com.example.drive.job.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.drive.job.domain.Operation;

public interface OperationRepository extends JpaRepository<Operation, UUID> {

	List<Operation> findByJob_IdOrderByOperationOrderAsc(UUID jobId);

	@Query("select o from Operation o join fetch o.job where o.id = :id")
	Optional<Operation> findByIdWithJob(@Param("id") UUID id);

	@Query("select o from Operation o join fetch o.job j left join fetch j.operations where o.id = :id")
	Optional<Operation> findByIdWithJobAndOperations(@Param("id") UUID id);
}
