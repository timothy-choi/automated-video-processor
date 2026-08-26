package com.example.drive.job.domain;

public enum OperationType {
	METADATA,
	THUMBNAIL,
	AUDIO_EXTRACTION,
	TRANSCODE_1080P,
	H264_TO_AV1;

	public boolean isExecutable() {
		return this == METADATA
				|| this == THUMBNAIL
				|| this == AUDIO_EXTRACTION
				|| this == TRANSCODE_1080P
				|| this == H264_TO_AV1;
	}
}
