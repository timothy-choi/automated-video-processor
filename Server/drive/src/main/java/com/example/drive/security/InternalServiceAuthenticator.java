package com.example.drive.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class InternalServiceAuthenticator {

	private final byte[] schedulerTokenHash;
	private final String workerTokenPepper;

	public InternalServiceAuthenticator(InternalAuthProperties properties) {
		this.schedulerTokenHash = sha256(properties.getSchedulerToken());
		this.workerTokenPepper = properties.getWorkerTokenPepper();
	}

	public Optional<InternalPrincipal> authenticate(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		if (MessageDigest.isEqual(schedulerTokenHash, sha256(rawToken))) {
			return Optional.of(new InternalPrincipal(InternalServiceType.SCHEDULER, "scheduler"));
		}
		return InternalWorkerTokens.authenticate(workerTokenPepper, rawToken)
				.map(workerId -> new InternalPrincipal(InternalServiceType.WORKER, workerId));
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(
					(value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
			);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required", ex);
		}
	}
}
