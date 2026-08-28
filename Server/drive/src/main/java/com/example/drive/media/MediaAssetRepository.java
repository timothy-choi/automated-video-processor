package com.example.drive.media;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.drive.media.domain.MediaAsset;

import jakarta.persistence.LockModeType;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

	Optional<MediaAsset> findByIdAndAccountId(UUID id, UUID accountId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from MediaAsset m where m.id = :id and m.accountId = :accountId")
	Optional<MediaAsset> findByIdAndAccountIdForUpdate(@Param("id") UUID id, @Param("accountId") UUID accountId);

	Page<MediaAsset> findByAccountId(UUID accountId, Pageable pageable);

	Page<MediaAsset> findByAccountIdAndStatus(UUID accountId, MediaAssetStatus status, Pageable pageable);
}
