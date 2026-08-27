package com.example.drive.storage;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactAccessPropertiesTest {

	@Test
	void defaultTtlIsFifteenMinutes() {
		assertThat(new ArtifactAccessProperties().getUrlTtl()).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void acceptsBoundedTtl() {
		ArtifactAccessProperties properties = new ArtifactAccessProperties();
		properties.setUrlTtl(Duration.ofMinutes(1));
		assertThat(properties.getUrlTtl()).isEqualTo(Duration.ofMinutes(1));
		properties.setUrlTtl(Duration.ofHours(24));
		assertThat(properties.getUrlTtl()).isEqualTo(Duration.ofHours(24));
	}

	@Test
	void rejectsOutOfRangeTtl() {
		ArtifactAccessProperties properties = new ArtifactAccessProperties();
		assertThatThrownBy(() -> properties.setUrlTtl(Duration.ofSeconds(30)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties.setUrlTtl(Duration.ofHours(25)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties.setUrlTtl(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
