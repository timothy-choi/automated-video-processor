package com.example.drive.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TimelineFailureSanitizerTest {

	@Test
	void keepsProductFailureReasons() {
		assertThat(TimelineFailureSanitizer.sanitize("FFmpeg exited non-zero")).isEqualTo("FFmpeg exited non-zero");
		assertThat(TimelineFailureSanitizer.sanitize("input has no audio stream")).isEqualTo("input has no audio stream");
		assertThat(TimelineFailureSanitizer.sanitize("source codec is not H.264")).isEqualTo("source codec is not H.264");
		assertThat(TimelineFailureSanitizer.sanitize("object not found")).isEqualTo("object not found");
	}

	@Test
	void stripsStackTracesAndExceptionPrefixes() {
		String raw = """
				java.lang.IllegalStateException: FFmpeg exited non-zero
					at com.example.drive.job.InternalOperationService.fail(InternalOperationService.java:12)
					at java.base/java.lang.Thread.run(Thread.java:840)
				""";
		assertThat(TimelineFailureSanitizer.sanitize(raw)).isEqualTo("FFmpeg exited non-zero");
	}

	@Test
	void redactsSecretsSignedUrlsAndConnectionStrings() {
		assertThat(TimelineFailureSanitizer.sanitize(
				"failed http://127.0.0.1:9000/x?X-Amz-Signature=abc&X-Amz-Credential=minioadmin"
		)).isEqualTo("Operation failed");
		assertThat(TimelineFailureSanitizer.sanitize("amqp://media_platform:media_platform@rabbitmq:5672/"))
				.isEqualTo("Operation failed");
		assertThat(TimelineFailureSanitizer.sanitize("token mp_live_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
				.isEqualTo("Operation failed");
		assertThat(TimelineFailureSanitizer.sanitize("Bearer super-secret")).isEqualTo("Operation failed");
	}

	@Test
	void returnsNullForBlank() {
		assertThat(TimelineFailureSanitizer.sanitize(null)).isNull();
		assertThat(TimelineFailureSanitizer.sanitize("   ")).isNull();
	}
}
