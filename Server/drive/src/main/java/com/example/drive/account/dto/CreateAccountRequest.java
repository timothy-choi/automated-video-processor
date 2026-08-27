package com.example.drive.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
		@NotBlank(message = "name is required")
		@Size(max = 128, message = "name must be at most 128 characters")
		String name
) {
}
