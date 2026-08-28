package com.example.drive.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelemetryRedactionTest {

	@Test
	void rejectsSecretsAndSignedQueryStrings() {
		assertThat(TelemetryRedaction.looksLikeSecret("mp_live_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")).isTrue();
		assertThat(TelemetryRedaction.looksLikeSecret("mp_wk_worker-a_deadbeef")).isTrue();
		assertThat(TelemetryRedaction.looksLikeSecret("Bearer super-secret")).isTrue();
		assertThat(TelemetryRedaction.looksLikeSecret("http://127.0.0.1:9000/media-output/x?X-Amz-Signature=abc&X-Amz-Credential=minioadmin")).isTrue();
		assertThat(TelemetryRedaction.looksLikeSecret("job-1")).isFalse();
		assertThat(TelemetryRedaction.isSensitiveKey("Authorization")).isTrue();
		assertThat(TelemetryRedaction.isSensitiveKey("worker_pepper")).isTrue();
		assertThat(TelemetryRedaction.isSensitiveKey("media.job.id")).isFalse();
		assertThat(TelemetryRedaction.boundedMessage("http://minio/x?X-Amz-Signature=abc")).isEqualTo("redacted");
	}

	@Test
	void noisyPathsExcludeHeartbeatLeaseAndIdleSnapshot() {
		assertThat(TelemetryNoise.isNoisyPath("/health")).isTrue();
		assertThat(TelemetryNoise.isNoisyPath("/internal/scheduler/snapshot")).isTrue();
		assertThat(TelemetryNoise.isNoisyPath("/internal/workers/worker-a/heartbeat")).isTrue();
		assertThat(TelemetryNoise.isNoisyPath("/internal/operations/op/attempts/att/renew")).isTrue();
		assertThat(TelemetryNoise.isNoisyPath("/jobs")).isFalse();
		assertThat(TelemetryNoise.isNoisyPath("/internal/scheduler/assign")).isFalse();
		assertThat(TelemetryNoise.isNoisyPath("/internal/operations/op/start")).isFalse();
	}
}
