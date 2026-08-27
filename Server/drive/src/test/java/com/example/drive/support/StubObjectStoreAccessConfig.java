package com.example.drive.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class StubObjectStoreAccessConfig {

	@Bean
	@Primary
	StubObjectStoreAccess objectStoreAccess() {
		return new StubObjectStoreAccess();
	}
}
