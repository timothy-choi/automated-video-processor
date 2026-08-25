package com.example.drive.dispatch;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchOutboxRepository extends JpaRepository<DispatchOutbox, UUID> {

	boolean existsByOperationId(UUID operationId);
}
