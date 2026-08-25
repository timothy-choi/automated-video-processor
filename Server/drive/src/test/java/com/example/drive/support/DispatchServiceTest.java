package com.example.drive.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
		"drive.dispatch.enabled=true",
		"drive.dispatch.scheduling-enabled=false",
		"drive.dispatch.publisher-enabled=false",
		"drive.dispatch.http-claim-enabled=false",
		"spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
})
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
public @interface DispatchServiceTest {
}
