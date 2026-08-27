package com.example.drive.storage;

import java.net.URI;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
class ObjectStoreConfiguration {

	private static final Logger log = LoggerFactory.getLogger(ObjectStoreConfiguration.class);

	private S3Client s3Client;
	private S3Presigner s3Presigner;

	@Bean
	@ConditionalOnMissingBean(ObjectStoreAccess.class)
	ObjectStoreAccess objectStoreAccess(ObjectStoreProperties properties, Clock clock) {
		URI internalEndpoint = URI.create(properties.getEndpoint());
		URI presignEndpoint = URI.create(properties.getPresignEndpoint());
		StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())
		);
		S3Configuration s3Config = S3Configuration.builder()
				.pathStyleAccessEnabled(properties.isForcePathStyle())
				.build();
		this.s3Client = S3Client.builder()
				.endpointOverride(internalEndpoint)
				.region(Region.of(properties.getRegion()))
				.credentialsProvider(credentials)
				.serviceConfiguration(s3Config)
				.httpClientBuilder(UrlConnectionHttpClient.builder())
				.build();
		this.s3Presigner = S3Presigner.builder()
				.endpointOverride(presignEndpoint)
				.region(Region.of(properties.getRegion()))
				.credentialsProvider(credentials)
				.serviceConfiguration(s3Config)
				.build();
		log.info(
				"event=object_store_configured endpoint={} presignEndpoint={}",
				properties.getEndpoint(),
				properties.getPresignEndpoint()
		);
		return new S3ObjectStoreAccess(s3Client, s3Presigner, clock);
	}

	@PreDestroy
	void close() {
		if (s3Presigner != null) {
			s3Presigner.close();
		}
		if (s3Client != null) {
			s3Client.close();
		}
	}
}
