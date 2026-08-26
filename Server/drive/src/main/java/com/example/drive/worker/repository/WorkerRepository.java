package com.example.drive.worker.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.drive.worker.domain.Worker;
import com.example.drive.worker.domain.WorkerStatus;

public interface WorkerRepository extends JpaRepository<Worker, String> {

	List<Worker> findAllByOrderByIdAsc();

	@Query("""
			select w.id from Worker w
			where w.status = :available
			  and (w.lastHeartbeat is null or w.lastHeartbeat < :cutoff)
			""")
	List<String> findStaleAvailableIds(
			@Param("available") WorkerStatus available,
			@Param("cutoff") Instant cutoff
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Worker w
			set w.status = :unavailable, w.updatedAt = :now
			where w.id = :id
			  and w.status = :available
			  and (w.lastHeartbeat is null or w.lastHeartbeat < :cutoff)
			""")
	int markUnavailableIfStale(
			@Param("id") String id,
			@Param("available") WorkerStatus available,
			@Param("unavailable") WorkerStatus unavailable,
			@Param("cutoff") Instant cutoff,
			@Param("now") Instant now
	);
}
