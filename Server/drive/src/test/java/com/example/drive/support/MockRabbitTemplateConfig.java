package com.example.drive.support;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
public class MockRabbitTemplateConfig {

	@Bean
	@Primary
	RabbitTemplate rabbitTemplate() {
		return mock(RabbitTemplate.class);
	}
}
