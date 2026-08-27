package com.example.drive.storage;

public class ObjectNotFoundException extends RuntimeException {

	public ObjectNotFoundException(String bucket, String key) {
		super("Object s3://" + bucket + "/" + key + " was not found");
	}
}
