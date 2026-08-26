package com.example.drive.scheduler;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPlacementTest {

	@Test
	void roundRobinRotatesAndWraps() {
		List<String> workers = List.of("worker-a", "worker-b", "worker-c");
		assertThat(WorkerPlacement.nextRoundRobin(workers, null)).isEqualTo("worker-a");
		assertThat(WorkerPlacement.nextRoundRobin(workers, "worker-a")).isEqualTo("worker-b");
		assertThat(WorkerPlacement.nextRoundRobin(workers, "worker-b")).isEqualTo("worker-c");
		assertThat(WorkerPlacement.nextRoundRobin(workers, "worker-c")).isEqualTo("worker-a");
	}

	@Test
	void roundRobinSkipsMissingLastWorker() {
		assertThat(WorkerPlacement.nextRoundRobin(List.of("worker-a", "worker-c"), "worker-b"))
				.isEqualTo("worker-c");
		assertThat(WorkerPlacement.nextRoundRobin(List.of("worker-a", "worker-c"), "worker-c"))
				.isEqualTo("worker-a");
	}

	@Test
	void lexicographicIsFirstEligible() {
		assertThat(WorkerPlacement.next(WorkerPlacement.LEXICOGRAPHIC, List.of("worker-b", "worker-c"), "worker-c"))
				.isEqualTo("worker-b");
	}
}
