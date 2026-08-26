package com.example.drive.scheduler;

import java.util.List;
import java.util.Map;

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

	@Test
	void leastLoadedPicksFewestActiveThenWorkerId() {
		Map<String, Integer> load = Map.of("worker-a", 2, "worker-b", 0, "worker-c", 1);
		assertThat(WorkerPlacement.leastLoaded(List.of("worker-a", "worker-b", "worker-c"), load))
				.isEqualTo("worker-b");
		assertThat(WorkerPlacement.leastLoaded(
				List.of("worker-a", "worker-b", "worker-c"),
				Map.of("worker-a", 1, "worker-b", 1, "worker-c", 2)
		)).isEqualTo("worker-a");
	}
}
