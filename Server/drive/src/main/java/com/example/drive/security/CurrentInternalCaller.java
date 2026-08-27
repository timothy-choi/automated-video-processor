package com.example.drive.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentInternalCaller {

	private static final Logger log = LoggerFactory.getLogger(CurrentInternalCaller.class);

	private CurrentInternalCaller() {
	}

	/**
	 * When an internal worker is authenticated, require {@code expectedWorkerId}
	 * to match the token subject. No-ops when SecurityContext is not an internal
	 * authentication so direct service tests keep working.
	 */
	public static void requireWorker(String expectedWorkerId) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof InternalAuthentication internal)) {
			return;
		}
		InternalPrincipal principal = internal.principal();
		if (principal.serviceType() != InternalServiceType.WORKER
				|| expectedWorkerId == null
				|| !principal.subjectId().equals(expectedWorkerId)) {
			log.warn(
					"event=internal_forbidden category=worker_identity_mismatch serviceType={} subjectId={}",
					principal.serviceType(),
					principal.subjectId()
			);
			throw new AccessDeniedException("Forbidden");
		}
	}
}
