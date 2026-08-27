package com.example.drive.account.dto;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyMetadataResponse(
		UUID id,
		String prefix,
		Instant createdAt,
		Instant revokedAt
) {
}
