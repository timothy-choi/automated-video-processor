package com.example.drive.scheduler.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.drive.scheduler.domain.SchedulingDecision;

public interface SchedulingDecisionRepository extends JpaRepository<SchedulingDecision, UUID> {

	List<SchedulingDecision> findByOperationIdOrderByCreatedAtAsc(UUID operationId);
}
