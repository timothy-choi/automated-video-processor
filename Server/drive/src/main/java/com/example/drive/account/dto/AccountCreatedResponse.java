package com.example.drive.account.dto;

public record AccountCreatedResponse(
		AccountResponse account,
		ApiKeyCreatedResponse apiKey
) {
}
