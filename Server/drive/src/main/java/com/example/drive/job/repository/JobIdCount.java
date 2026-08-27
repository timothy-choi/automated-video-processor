package com.example.drive.job.repository;

import java.util.UUID;

public interface JobIdCount {

	UUID getJobId();

	long getCount();
}
