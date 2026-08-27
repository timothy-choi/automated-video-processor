package com.example.drive.storage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	S3ObjectStoreAccess(S3Client s3Client, S3Presigner s3Presigner, Clock clock) {
		this.s3Client = s3Client;
		this.s3Presigner = s3Presigner;
		this.clock = clock;
	}

	@Override
	public void verifyObjectExists(String bucket, String key) {
		try {
			s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
		}
		catch (NoSuchKeyException | NoSuchBucketException ex) {
			throw new ObjectNotFoundException(bucket, key);
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				throw new ObjectNotFoundException(bucket, key);
			}
			log.warn("event=object_store_head_failed bucket={} key={} status={}", bucket, key, ex.statusCode());
			throw new ObjectStoreUnavailableException("Object store request failed");
		}
		catch (AwsServiceException | SdkClientException ex) {
			log.warn("event=object_store_unavailable bucket={} key={}", bucket, key);
			throw new ObjectStoreUnavailableException("Object store is unavailable");
		}
	}

	@Override
	public PresignedGet presignGet(String bucket, String key, Duration ttl) {
		try {
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
			throw ex;
		}
		catch (Exception ex) {
			log.warn("event=object_store_presign_failed bucket={} key={}", bucket, key);
			throw new ObjectStoreUnavailableException("Object store is unavailable");
		}
	}
}
