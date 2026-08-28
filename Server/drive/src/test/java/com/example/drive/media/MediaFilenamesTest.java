package com.example.drive.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaFilenamesTest {

	@Test
	void stripsPathsAndControlsAndCapsLength() {
		assertThat(MediaFilenames.sanitize("../../etc/passwd")).isEqualTo("passwd");
		assertThat(MediaFilenames.sanitize("dir\\clip.mp4")).isEqualTo("clip.mp4");
		assertThat(MediaFilenames.sanitize("a\nb.mp4")).isEqualTo("ab.mp4");
		assertThat(MediaFilenames.sanitize("..")).isEqualTo("upload.bin");
		assertThat(MediaFilenames.sanitize("")).isEqualTo("upload.bin");
		assertThat(MediaFilenames.sanitize("x".repeat(300))).hasSize(255);
	}
}
