package com.example.drive.worker.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.drive.worker.domain.Worker;

public interface WorkerRepository extends JpaRepository<Worker, String> {

	List<Worker> findAllByOrderByIdAsc();
}
