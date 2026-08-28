package com.example.drive.storage;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.example.drive.support.ControlServiceTest;

import static org.assertj.core.api.Assertions.assertThat;

@ControlServiceTest
@TestPropertySource(properties = {
		"drive.object-store.endpoint=http://minio.internal:9000",
		"drive.object-store.public-endpoint=http://127.0.0.1:19000"
})
class ObjectStorePresignEndpointTest {

	@Autowired
	private ObjectStoreAccess objectStoreAccess;

	@Autowired
	private ObjectStoreProperties properties;

	@Test
	void signedUrlUsesPublicHostWithoutRewritingAfterSignature() {
		assertThat(properties.getEndpoint()).isEqualTo("http://minio.internal:9000");
		assertThat(properties.getPresignEndpoint()).isEqualTo("http://127.0.0.1:19000");

		ObjectStoreAccess.PresignedGet signed = objectStoreAccess.presignGet(
				"media-output",
				"jobs/demo/thumbnail.jpg",
				Duration.ofMinutes(15)
		);

		assertThat(signed.url().getHost()).isEqualTo("127.0.0.1");
		assertThat(signed.url().getPort()).isEqualTo(19000);
		assertThat(signed.url().getPath()).isEqualTo("/media-output/jobs/demo/thumbnail.jpg");
		assertThat(signed.url().getQuery()).contains("X-Amz-Signature");
		assertThat(signed.url().toString()).doesNotContain("minio.internal");
	}

	@Test
	void signedPutUrlUsesPublicHostWithoutRewritingAfterSignature() {
		ObjectStoreAccess.PresignedPut signed = objectStoreAccess.presignPut(
				"media-input",
				"accounts/demo/media/demo/source",
				"video/mp4",
				Duration.ofMinutes(15)
		);

		assertThat(signed.url().getHost()).isEqualTo("127.0.0.1");
		assertThat(signed.url().getPort()).isEqualTo(19000);
		assertThat(signed.url().getPath()).isEqualTo("/media-input/accounts/demo/media/demo/source");
		assertThat(signed.url().getQuery()).contains("X-Amz-Signature");
		assertThat(signed.url().getQuery()).contains("X-Amz-Expires");
		assertThat(signed.url().toString()).doesNotContain("minio.internal");
		assertThat(signed.url().getQuery()).doesNotContain("X-Amz-Security-Token");
	}
}
