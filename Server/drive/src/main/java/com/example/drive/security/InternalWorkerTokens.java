package com.example.drive.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Per-worker HMAC bearer tokens.
 *
 * <p>Format: {@code mp_wk_<workerId>_<hmac-sha256-hex>} where HMAC is keyed by
 * the control-plane pepper over {@code WORKER:<workerId>}. Workers receive only
 * the derived token, not the pepper, so they cannot mint another worker's
 * credential. The pepper is environment-managed and never persisted.
 */
public final class InternalWorkerTokens {

	public static final String PREFIX = "mp_wk_";

	private static final Pattern WORKER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
	private static final Pattern HMAC_HEX = Pattern.compile("^[0-9a-f]{64}$");

	private InternalWorkerTokens() {
	}

	public static String issue(String pepper, String workerId) {
		if (pepper == null || pepper.isBlank()) {
			throw new IllegalArgumentException("worker token pepper is required");
		}
		if (workerId == null || !WORKER_ID_PATTERN.matcher(workerId).matches()) {
			throw new IllegalArgumentException("workerId is invalid");
		}
		return PREFIX + workerId + "_" + hmacHex(pepper, workerId);
	}

	public static Optional<String> authenticate(String pepper, String token) {
		if (pepper == null || pepper.isBlank() || token == null || token.isBlank()) {
			return Optional.empty();
		}
		Optional<Parsed> parsed = parse(token);
		if (parsed.isEmpty()) {
			return Optional.empty();
		}
		byte[] expected = hmacBytes(pepper, parsed.get().workerId());
		byte[] provided = HexFormat.of().parseHex(parsed.get().macHex());
		if (!MessageDigest.isEqual(expected, provided)) {
			return Optional.empty();
		}
		return Optional.of(parsed.get().workerId());
	}

	public static Optional<String> subject(String token) {
		return parse(token).map(Parsed::workerId);
	}

	private static Optional<Parsed> parse(String token) {
		if (!token.startsWith(PREFIX)) {
			return Optional.empty();
		}
		String rest = token.substring(PREFIX.length());
		int separator = rest.lastIndexOf('_');
		if (separator <= 0 || separator >= rest.length() - 1) {
			return Optional.empty();
		}
		String workerId = rest.substring(0, separator);
		String macHex = rest.substring(separator + 1);
		if (!WORKER_ID_PATTERN.matcher(workerId).matches() || !HMAC_HEX.matcher(macHex).matches()) {
			return Optional.empty();
		}
		return Optional.of(new Parsed(workerId, macHex));
	}

	private static String hmacHex(String pepper, String workerId) {
		return HexFormat.of().formatHex(hmacBytes(pepper, workerId));
	}

	private static byte[] hmacBytes(String pepper, String workerId) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return mac.doFinal(("WORKER:" + workerId).getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HMAC-SHA256 is required", ex);
		}
	}

	private record Parsed(String workerId, String macHex) {
	}
}
