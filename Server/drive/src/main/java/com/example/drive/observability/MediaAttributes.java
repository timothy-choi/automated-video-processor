package com.example.drive.observability;

import io.opentelemetry.api.common.AttributeKey;

public final class MediaAttributes {

	public static final AttributeKey<String> JOB_ID = AttributeKey.stringKey("media.job.id");
	public static final AttributeKey<String> ACCOUNT_ID = AttributeKey.stringKey("media.account.id");
	public static final AttributeKey<String> JOB_PRIORITY = AttributeKey.stringKey("media.job.priority");
	public static final AttributeKey<Long> OPERATION_COUNT = AttributeKey.longKey("media.job.operation_count");
	public static final AttributeKey<String> OPERATION_ID = AttributeKey.stringKey("media.operation.id");
	public static final AttributeKey<String> OPERATION_TYPE = AttributeKey.stringKey("media.operation.type");
	public static final AttributeKey<String> ATTEMPT_ID = AttributeKey.stringKey("media.attempt.id");
	public static final AttributeKey<String> WORKER_ID = AttributeKey.stringKey("media.worker.id");
	public static final AttributeKey<String> OPERATION_POLICY = AttributeKey.stringKey("media.scheduler.operation_policy");
	public static final AttributeKey<String> WORKER_POLICY = AttributeKey.stringKey("media.scheduler.worker_policy");
	public static final AttributeKey<Long> ACTIVE_OPERATIONS = AttributeKey.longKey("media.scheduler.active_operations");
	public static final AttributeKey<String> OBJECT_BUCKET = AttributeKey.stringKey("media.objectstore.bucket");
	public static final AttributeKey<String> OBJECT_OPERATION = AttributeKey.stringKey("media.objectstore.operation");
	public static final AttributeKey<String> MEDIA_ASSET_ID = AttributeKey.stringKey("media.asset.id");
	public static final AttributeKey<String> MEDIA_ASSET_STATUS = AttributeKey.stringKey("media.asset.status");
	public static final AttributeKey<Boolean> OPERATION_CANCELLED = AttributeKey.booleanKey("operation.cancelled");

	private MediaAttributes() {
	}
}
