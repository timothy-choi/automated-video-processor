package com.example.drive.worker.dto;

import com.example.drive.worker.domain.Worker;

public record RegistrationResult(RegisterWorkerResponse worker, boolean created) {

	public static RegistrationResult created(Worker worker) {
		return new RegistrationResult(RegisterWorkerResponse.from(worker), true);
	}

	public static RegistrationResult updated(Worker worker) {
		return new RegistrationResult(RegisterWorkerResponse.from(worker), false);
	}
}
