package com.example.drive.media;

import java.util.UUID;

final class MediaObjectKeys {

	private MediaObjectKeys() {
	}

	static String sourceKey(UUID accountId, UUID assetId) {
		return "accounts/" + accountId + "/media/" + assetId + "/source";
	}
}
