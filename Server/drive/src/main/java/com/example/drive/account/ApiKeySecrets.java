package com.example.drive.account;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Generates and validates the external API-key format {@code mp_live_<64 hex chars>}.
 * The secret is 32 CSPRNG bytes, not a UUID.
 */
public final class ApiKeySecrets {

	public static final String PREFIX = "mp_live_";

	public static final int PREFIX_DISPLAY_LENGTH = 12;

	private static final int SECRET_BYTES = 32;

	private static final Pattern RAW_KEY = Pattern.compile("^mp_live_[0-9a-f]{64}$");

	private static final SecureRandom RANDOM = new SecureRandom();

	private ApiKeySecrets() {
	}

	public static String generate() {
		byte[] secret = new byte[SECRET_BYTES];
		RANDOM.nextBytes(secret);
		return PREFIX + HexFormat.of().formatHex(secret);
	}

	public static String displayPrefix(String rawKey) {
		if (rawKey == null || rawKey.length() < PREFIX_DISPLAY_LENGTH) {
			return PREFIX;
		}
		return rawKey.substring(0, PREFIX_DISPLAY_LENGTH);
	}

	public static boolean isWellFormed(String rawKey) {
		return rawKey != null && RAW_KEY.matcher(rawKey).matches();
	}
}
