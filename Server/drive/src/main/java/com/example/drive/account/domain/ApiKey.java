package com.example.drive.account.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "api_keys")
public class ApiKey {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@Column(name = "key_prefix", nullable = false, length = 32)
	private String keyPrefix;

	@Column(name = "key_hash", nullable = false, length = 64)
	private String keyHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected ApiKey() {
	}

	public ApiKey(UUID id, Account account, String keyPrefix, String keyHash, Instant createdAt) {
		this.id = id;
		this.account = account;
		this.keyPrefix = keyPrefix;
		this.keyHash = keyHash;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public Account getAccount() {
		return account;
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	public String getKeyHash() {
		return keyHash;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public void revoke(Instant now) {
		if (revokedAt == null) {
			revokedAt = now;
		}
	}
}
