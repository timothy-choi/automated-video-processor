package com.example.drive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.drive.dispatch.DispatchProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(DispatchProperties.class)
public class DriveApplication {

	public static void main(String[] args) {
		SpringApplication.run(DriveApplication.class, args);
	}
}
