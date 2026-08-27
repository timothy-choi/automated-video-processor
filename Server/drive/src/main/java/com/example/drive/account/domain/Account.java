package com.example.drive.account.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private AccountStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Account() {
	}

	public Account(UUID id, String name, Instant createdAt) {
		this.id = id;
		this.name = name;
		this.status = AccountStatus.ACTIVE;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public AccountStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public boolean isActive() {
		return status == AccountStatus.ACTIVE;
	}
}
