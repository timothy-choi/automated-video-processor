package com.example.drive.job.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.drive.job.domain.Operation;

public interface OperationRepository extends JpaRepository<Operation, UUID> {

	List<Operation> findByJob_IdOrderByOperationOrderAsc(UUID jobId);
}
