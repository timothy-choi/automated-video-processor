package com.example.drive.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestcontainersConfig {

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbitmqContainer() {
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));
	}
}
