package com.example.drive.media;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUploadPropertiesTest {

	@Test
	void defaultsAreProductSized() {
		MediaUploadProperties properties = new MediaUploadProperties();
		assertThat(properties.getMaxBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
		assertThat(properties.getUrlTtl()).isEqualTo(Duration.ofMinutes(15));
		assertThat(properties.getInputBucket()).isEqualTo("media-input");
	}

	@Test
	void rejectsOutOfRangeTtlAndNonPositiveMax() {
		MediaUploadProperties properties = new MediaUploadProperties();
		assertThatThrownBy(() -> properties.setUrlTtl(Duration.ofSeconds(30)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties.setUrlTtl(Duration.ofHours(25)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties.setMaxBytes(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties.setInputBucket(" "))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
