package com.example.drive.security;

import java.util.UUID;

public record AccountPrincipal(UUID accountId, UUID apiKeyId) {
}
