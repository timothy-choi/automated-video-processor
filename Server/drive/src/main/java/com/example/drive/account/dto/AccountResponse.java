package com.example.drive.account.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.drive.account.domain.AccountStatus;

public record AccountResponse(
		UUID id,
		String name,
		AccountStatus status,
		Instant createdAt
) {
}
