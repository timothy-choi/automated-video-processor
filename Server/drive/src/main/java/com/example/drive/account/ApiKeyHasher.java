package com.example.drive.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way representation of an API key for lookup.
 *
 * <p>API keys are 256-bit CSPRNG secrets ({@code mp_live_} + 32 random bytes as
 * hex). That entropy is in the same range as a SHA-256 digest, so a single
 * SHA-256 of the raw key is an appropriate one-way store: it is not a
 * human-chosen password and does not need a slow KDF. The digest is unique
 * (indexed) so authentication is a point lookup, never a table scan. The
 * stored prefix is not used to authenticate.
 *
 * <p>The digest is never logged or returned on list/revoke responses.
 */
public final class ApiKeyHasher {

	private ApiKeyHasher() {
	}

	public static String sha256Hex(String rawKey) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required", ex);
		}
	}
}
