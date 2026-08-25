package com.example.drive.health;

import org.springframework.stereotype.Service;

@Service
public class HealthService {

	public HealthResponse currentHealth() {
		return new HealthResponse("UP");
	}
}
