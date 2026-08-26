package com.example.drive.scheduler;

import java.util.List;

/**
 * Worker-placement helpers. Operation ordering is FIFO and is not implemented here.
 */
public final class WorkerPlacement {

	public static final String LEXICOGRAPHIC = "LEXICOGRAPHIC";
	public static final String ROUND_ROBIN = "ROUND_ROBIN";

	private WorkerPlacement() {
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
		throw new IllegalArgumentException("unsupported worker policy " + workerPolicy);
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
