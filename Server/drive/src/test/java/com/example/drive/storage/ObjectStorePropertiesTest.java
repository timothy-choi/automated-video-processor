package com.example.drive.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ObjectStorePropertiesTest {

	@Test
	void presignEndpointFallsBackToInternalEndpointWhenPublicBlank() {
		ObjectStoreProperties properties = new ObjectStoreProperties();
		properties.setEndpoint("http://minio:9000");
		assertThat(properties.getPresignEndpoint()).isEqualTo("http://minio:9000");
	}

	@Test
	void presignEndpointUsesPublicWhenSet() {
		ObjectStoreProperties properties = new ObjectStoreProperties();
		properties.setEndpoint("http://minio:9000");
		properties.setPublicEndpoint("http://127.0.0.1:9000");
		assertThat(properties.getEndpoint()).isEqualTo("http://minio:9000");
		assertThat(properties.getPresignEndpoint()).isEqualTo("http://127.0.0.1:9000");
	}

	@Test
	void blankPublicEndpointIsTrimmedToFallback() {
		ObjectStoreProperties properties = new ObjectStoreProperties();
		properties.setEndpoint("http://minio:9000");
		properties.setPublicEndpoint("  ");
		assertThat(properties.getPresignEndpoint()).isEqualTo("http://minio:9000");
	}

	@Test
	void blankInternalEndpointIsRejected() {
		ObjectStoreProperties properties = new ObjectStoreProperties();
		assertThatThrownBy(() -> properties.setEndpoint(" "))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
