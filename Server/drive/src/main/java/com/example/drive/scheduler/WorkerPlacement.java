package com.example.drive.scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Worker-placement helpers. Operation ordering is FIFO and is not implemented here.
 * Least Loaded is computed by the Go scheduler; Java does not re-check optimality.
 */
public final class WorkerPlacement {

	public static final String LEXICOGRAPHIC = "LEXICOGRAPHIC";
	public static final String ROUND_ROBIN = "ROUND_ROBIN";
	public static final String LEAST_LOADED = "LEAST_LOADED";

	private WorkerPlacement() {
	}

	public static boolean isSupported(String workerPolicy) {
		return LEXICOGRAPHIC.equals(workerPolicy)
				|| ROUND_ROBIN.equals(workerPolicy)
				|| LEAST_LOADED.equals(workerPolicy);
	}

	public static String next(String workerPolicy, List<String> eligibleSorted, String lastWorkerId) {
		if (eligibleSorted == null || eligibleSorted.isEmpty()) {
			return null;
		}
		if (LEXICOGRAPHIC.equals(workerPolicy)) {
			return eligibleSorted.get(0);
		}
		if (ROUND_ROBIN.equals(workerPolicy)) {
			return nextRoundRobin(eligibleSorted, lastWorkerId);
		}
		if (LEAST_LOADED.equals(workerPolicy)) {
			throw new IllegalArgumentException("LEAST_LOADED requires active-operation counts; use leastLoaded()");
		}
		throw new IllegalArgumentException("unsupported worker policy " + workerPolicy);
	}

	public static String leastLoaded(List<String> eligibleSorted, Map<String, Integer> activeOperations) {
		if (eligibleSorted == null || eligibleSorted.isEmpty()) {
			return null;
		}
		return eligibleSorted.stream()
				.min(Comparator
						.comparingInt((String id) -> activeOperations.getOrDefault(id, 0))
						.thenComparing(id -> id))
				.orElse(null);
	}

	static String nextRoundRobin(List<String> eligibleSorted, String lastWorkerId) {
		if (lastWorkerId == null || lastWorkerId.isBlank()) {
			return eligibleSorted.get(0);
		}
		for (String workerId : eligibleSorted) {
			if (workerId.compareTo(lastWorkerId) > 0) {
				return workerId;
			}
		}
		return eligibleSorted.get(0);
	}
}
