package com.example.drive.media;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public class MediaAssetException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public MediaAssetException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public static MediaAssetException notFound(UUID id) {
		return new MediaAssetException(
				HttpStatus.NOT_FOUND,
				"MEDIA_ASSET_NOT_FOUND",
				"Media asset " + id + " was not found"
		);
	}

	public static MediaAssetException notReady(UUID id) {
		return new MediaAssetException(
				HttpStatus.CONFLICT,
				"MEDIA_ASSET_NOT_READY",
				"Media asset " + id + " is not READY"
		);
	}

	public static MediaAssetException alreadyReady(UUID id) {
		return new MediaAssetException(
				HttpStatus.CONFLICT,
				"MEDIA_ASSET_ALREADY_READY",
				"Media asset " + id + " is already READY"
		);
	}

	public static MediaAssetException inUse(UUID id) {
		return new MediaAssetException(
				HttpStatus.CONFLICT,
				"MEDIA_ASSET_IN_USE",
				"Media asset " + id + " is referenced by a Job"
		);
	}

	public static MediaAssetException uploadObjectNotFound() {
		return new MediaAssetException(
				HttpStatus.CONFLICT,
				"UPLOAD_OBJECT_NOT_FOUND",
				"Uploaded object was not found in object storage"
		);
	}

	public static MediaAssetException uploadObjectEmpty() {
		return new MediaAssetException(
				HttpStatus.CONFLICT,
				"UPLOAD_OBJECT_EMPTY",
				"Uploaded object is empty"
		);
	}

	public static MediaAssetException uploadTooLarge(HttpStatus status) {
		return new MediaAssetException(
				status,
				"UPLOAD_TOO_LARGE",
				"Media exceeds the configured upload size limit"
		);
	}

	public static MediaAssetException invalidContentType() {
		return new MediaAssetException(
				HttpStatus.BAD_REQUEST,
				"INVALID_CONTENT_TYPE",
				"contentType must be video/*, audio/*, or application/octet-stream"
		);
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}
}
