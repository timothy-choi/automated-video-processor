package com.example.drive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.drive.dispatch.DispatchProperties;
import com.example.drive.job.OperationAssignmentProperties;
import com.example.drive.job.OperationLeaseProperties;
import com.example.drive.storage.ArtifactAccessProperties;
import com.example.drive.storage.ObjectStoreProperties;
import com.example.drive.worker.WorkerHeartbeatProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
		DispatchProperties.class,
		WorkerHeartbeatProperties.class,
		OperationLeaseProperties.class,
		OperationAssignmentProperties.class,
		ObjectStoreProperties.class,
		ArtifactAccessProperties.class
})
public class DriveApplication {

	public static void main(String[] args) {
		SpringApplication.run(DriveApplication.class, args);
	}
}
