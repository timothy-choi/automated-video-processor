package com.example.drive.account.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.drive.account.domain.ApiKey;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

	@Query("select k from ApiKey k join fetch k.account where k.keyHash = :keyHash")
	Optional<ApiKey> findByKeyHashWithAccount(@Param("keyHash") String keyHash);

	List<ApiKey> findByAccount_IdOrderByCreatedAtAsc(UUID accountId);

	Optional<ApiKey> findByIdAndAccount_Id(UUID id, UUID accountId);

	boolean existsByKeyHash(String keyHash);
}
