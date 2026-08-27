package com.example.drive.storage;

import org.junit.jupiter.api.Test;

import com.example.drive.job.ArtifactUriInvalidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3ObjectLocatorTest {

	@Test
	void parseSimpleAndNestedKeys() {
		S3ObjectLocator simple = S3ObjectLocator.parse("s3://media-output/thumbnail.jpg");
		assertThat(simple.bucket()).isEqualTo("media-output");
		assertThat(simple.key()).isEqualTo("thumbnail.jpg");
		assertThat(simple.fileName()).isEqualTo("thumbnail.jpg");

		S3ObjectLocator nested = S3ObjectLocator.parse(
				"s3://media-output/jobs/job-1/operations/op-2/video-av1.mp4"
		);
		assertThat(nested.bucket()).isEqualTo("media-output");
		assertThat(nested.key()).isEqualTo("jobs/job-1/operations/op-2/video-av1.mp4");
		assertThat(nested.fileName()).isEqualTo("video-av1.mp4");
		assertThat(nested.canonicalUri()).isEqualTo("s3://media-output/jobs/job-1/operations/op-2/video-av1.mp4");
	}

	@Test
	void rejectInvalidUris() {
		assertThatThrownBy(() -> S3ObjectLocator.parse(" "))
				.isInstanceOf(ArtifactUriInvalidException.class);
		assertThatThrownBy(() -> S3ObjectLocator.parse("file:///tmp/thumbnail.jpg"))
				.isInstanceOf(ArtifactUriInvalidException.class)
				.hasMessageContaining("s3://");
		assertThatThrownBy(() -> S3ObjectLocator.parse("https://example.com/file.jpg"))
				.isInstanceOf(ArtifactUriInvalidException.class);
		assertThatThrownBy(() -> S3ObjectLocator.parse("s3://"))
				.isInstanceOf(ArtifactUriInvalidException.class);
		assertThatThrownBy(() -> S3ObjectLocator.parse("s3:///video.mp4"))
				.isInstanceOf(ArtifactUriInvalidException.class);
		assertThatThrownBy(() -> S3ObjectLocator.parse("s3://bucket"))
				.isInstanceOf(ArtifactUriInvalidException.class);
		assertThatThrownBy(() -> S3ObjectLocator.parse("s3://bucket/"))
				.isInstanceOf(ArtifactUriInvalidException.class);
	}
}
