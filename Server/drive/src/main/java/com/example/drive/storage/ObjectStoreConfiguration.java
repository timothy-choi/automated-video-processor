package com.example.drive.storage;

import java.net.URI;
import java.time.Clock;

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

	private S3Client s3Client;
	private S3Presigner s3Presigner;

	@Bean
	@ConditionalOnMissingBean(ObjectStoreAccess.class)
	ObjectStoreAccess objectStoreAccess(ObjectStoreProperties properties, Clock clock) {
		URI endpoint = URI.create(properties.getEndpoint());
		StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())
		);
		S3Configuration s3Config = S3Configuration.builder()
				.pathStyleAccessEnabled(properties.isForcePathStyle())
				.build();
		this.s3Client = S3Client.builder()
				.endpointOverride(endpoint)
				.region(Region.of(properties.getRegion()))
				.credentialsProvider(credentials)
				.serviceConfiguration(s3Config)
				.httpClientBuilder(UrlConnectionHttpClient.builder())
				.build();
		this.s3Presigner = S3Presigner.builder()
				.endpointOverride(endpoint)
				.region(Region.of(properties.getRegion()))
				.credentialsProvider(credentials)
				.serviceConfiguration(s3Config)
				.build();
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
