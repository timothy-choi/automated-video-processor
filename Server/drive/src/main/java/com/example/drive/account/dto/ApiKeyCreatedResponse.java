package com.example.drive.account.dto;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyCreatedResponse(
		UUID id,
		String key,
		String prefix,
		Instant createdAt
) {
}
