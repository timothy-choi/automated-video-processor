package com.example.drive.storage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.drive.observability.MediaAttributes;
import com.example.drive.observability.MediaSpans;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

final class S3ObjectStoreAccess implements ObjectStoreAccess {

	private static final Logger log = LoggerFactory.getLogger(S3ObjectStoreAccess.class);

	private final S3Client s3Client;
	private final S3Presigner s3Presigner;
	private final Clock clock;
	private final Tracer tracer;

	S3ObjectStoreAccess(S3Client s3Client, S3Presigner s3Presigner, Clock clock, Tracer tracer) {
		this.s3Client = s3Client;
		this.s3Presigner = s3Presigner;
		this.clock = clock;
		this.tracer = tracer;
	}

	@Override
	public void verifyObjectExists(String bucket, String key) {
		Span span = MediaSpans.start(tracer, MediaSpans.OBJECTSTORE_HEAD);
		try (Scope ignored = span.makeCurrent()) {
			MediaSpans.set(span, MediaAttributes.OBJECT_BUCKET, bucket);
			MediaSpans.set(span, MediaAttributes.OBJECT_OPERATION, "head");
			s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
		}
		catch (NoSuchKeyException | NoSuchBucketException ex) {
			MediaSpans.recordError(span, ex);
			throw new ObjectNotFoundException(bucket, key);
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				MediaSpans.recordError(span, ex);
				throw new ObjectNotFoundException(bucket, key);
			}
			log.warn("event=object_store_head_failed bucket={} key={} status={}", bucket, key, ex.statusCode());
			MediaSpans.recordError(span, ex);
			throw new ObjectStoreUnavailableException("Object store request failed");
		}
		catch (AwsServiceException | SdkClientException ex) {
			log.warn("event=object_store_unavailable bucket={} key={}", bucket, key);
			MediaSpans.recordError(span, ex);
			throw new ObjectStoreUnavailableException("Object store is unavailable");
		}
		finally {
			span.end();
		}
	}

	@Override
	public PresignedGet presignGet(String bucket, String key, Duration ttl) {
		Span span = MediaSpans.start(tracer, MediaSpans.OBJECTSTORE_PRESIGN);
		try (Scope ignored = span.makeCurrent()) {
			MediaSpans.set(span, MediaAttributes.OBJECT_BUCKET, bucket);
			MediaSpans.set(span, MediaAttributes.OBJECT_OPERATION, "presign");
			PresignedGetObjectRequest signed = s3Presigner.presignGetObject(
					GetObjectPresignRequest.builder()
							.signatureDuration(ttl)
							.getObjectRequest(req -> req.bucket(bucket).key(key))
							.build()
			);
			Instant expiresAt = Instant.now(clock).plus(ttl).truncatedTo(ChronoUnit.SECONDS);
			return new PresignedGet(signed.url().toURI(), expiresAt);
		}
		catch (ObjectStoreUnavailableException | ObjectNotFoundException ex) {
			MediaSpans.recordError(span, ex);
			throw ex;
		}
		catch (Exception ex) {
			log.warn("event=object_store_presign_failed bucket={} key={}", bucket, key);
			MediaSpans.recordError(span, ex);
			throw new ObjectStoreUnavailableException("Object store is unavailable");
		}
		finally {
			span.end();
		}
	}
}
