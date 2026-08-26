package com.example.drive.worker;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.domain.OperationType;
import com.example.drive.worker.domain.Worker;
import com.example.drive.worker.domain.WorkerStatus;
import com.example.drive.worker.dto.HeartbeatResponse;
import com.example.drive.worker.dto.RegisterWorkerRequest;
import com.example.drive.worker.dto.RegistrationResult;
import com.example.drive.worker.dto.WorkerResponse;
import com.example.drive.worker.dto.WorkersResponse;
import com.example.drive.worker.repository.WorkerRepository;

@Service
public class WorkerService {

	private static final Logger log = LoggerFactory.getLogger(WorkerService.class);
	private static final Pattern WORKER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
	private static final Pattern CODEC_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,31}$");
	private static final Set<String> CANONICAL_CODECS = Set.of("h264", "hevc", "av1", "vp9");

	private final WorkerRepository workerRepository;
	private final WorkerHeartbeatProperties heartbeatProperties;
	private final Clock clock;

	public WorkerService(
			WorkerRepository workerRepository,
			WorkerHeartbeatProperties heartbeatProperties,
			Clock clock
	) {
		this.workerRepository = workerRepository;
		this.heartbeatProperties = heartbeatProperties;
		this.clock = clock;
	}

	@Transactional
	public RegistrationResult register(RegisterWorkerRequest request) {
		if (request == null) {
			throw new InvalidRegistrationException("INVALID_REGISTRATION", "registration body is required");
		}
		String workerId = requireWorkerId(request.workerId());
		String hostname = requireText(request.hostname(), "hostname");
		String cpuArchitecture = requireText(request.cpuArchitecture(), "cpuArchitecture");
		int cpuCores = requireCpuCores(request.cpuCores());
		long memoryBytes = requireMemoryBytes(request.memoryBytes());
		Set<OperationType> operations = normalizeOperations(request.supportedOperations());
		Set<String> codecs = normalizeCodecs(request.supportedCodecs());
		String ffmpegVersion = normalizeFfmpegVersion(request.ffmpegVersion());
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

		return workerRepository.findById(workerId)
				.map(existing -> {
					WorkerStatus previous = existing.getStatus();
					existing.refreshRegistration(hostname, cpuArchitecture, cpuCores, memoryBytes, ffmpegVersion, now);
					existing.replaceCapabilities(operations, codecs);
					logTransition(workerId, previous, existing.getStatus());
					return RegistrationResult.updated(existing);
				})
				.orElseGet(() -> {
					Worker created = new Worker(
							workerId,
							hostname,
							cpuArchitecture,
							cpuCores,
							memoryBytes,
							ffmpegVersion,
							now
					);
					created.replaceCapabilities(operations, codecs);
					return RegistrationResult.created(workerRepository.save(created));
				});
	}

	@Transactional
	public HeartbeatResponse heartbeat(String workerId) {
		Worker worker = workerRepository.findById(workerId)
				.orElseThrow(() -> new WorkerNotFoundException(workerId));
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		WorkerStatus previous = worker.getStatus();
		worker.recordHeartbeat(now);
		logTransition(workerId, previous, worker.getStatus());
		return HeartbeatResponse.from(worker);
	}

	@Transactional
	public int markStaleWorkers() {
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Instant cutoff = now.minus(heartbeatProperties.getHeartbeatTimeout());
		List<String> staleIds = workerRepository.findStaleAvailableIds(WorkerStatus.AVAILABLE, cutoff);
		int marked = 0;
		for (String workerId : staleIds) {
			int updated = workerRepository.markUnavailableIfStale(
					workerId,
					WorkerStatus.AVAILABLE,
					WorkerStatus.UNAVAILABLE,
					cutoff,
					now
			);
			if (updated == 1) {
				logTransition(workerId, WorkerStatus.AVAILABLE, WorkerStatus.UNAVAILABLE);
				marked++;
			}
		}
		return marked;
	}

	@Transactional(readOnly = true)
	public WorkersResponse listWorkers() {
		return new WorkersResponse(
				workerRepository.findAllByOrderByIdAsc().stream()
						.map(WorkerResponse::from)
						.toList()
		);
	}

	@Transactional(readOnly = true)
	public WorkerResponse getWorker(String workerId) {
		return workerRepository.findById(workerId)
				.map(WorkerResponse::from)
				.orElseThrow(() -> new WorkerNotFoundException(workerId));
	}

	private static void logTransition(String workerId, WorkerStatus from, WorkerStatus to) {
		if (from != to) {
			log.info("worker_id={} event=status_transition from={} to={}", workerId, from, to);
		}
	}

	private static String requireWorkerId(String workerId) {
		if (workerId == null || workerId.isBlank()) {
			throw new InvalidRegistrationException("INVALID_REGISTRATION", "workerId is required");
		}
		String trimmed = workerId.trim();
		if (!WORKER_ID_PATTERN.matcher(trimmed).matches()) {
			throw new InvalidRegistrationException(
					"INVALID_REGISTRATION",
					"workerId must be 1-64 characters using letters, digits, '.', '_' or '-'"
			);
		}
		return trimmed;
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new InvalidRegistrationException("INVALID_REGISTRATION", field + " is required");
		}
		return value.trim();
	}

	private static int requireCpuCores(Integer cpuCores) {
		if (cpuCores == null || cpuCores <= 0) {
			throw new InvalidRegistrationException("INVALID_REGISTRATION", "cpuCores must be greater than 0");
		}
		return cpuCores;
	}

	private static long requireMemoryBytes(Long memoryBytes) {
		if (memoryBytes == null || memoryBytes < 0) {
			throw new InvalidRegistrationException("INVALID_REGISTRATION", "memoryBytes must be greater than or equal to 0");
		}
		return memoryBytes;
	}

	private static Set<OperationType> normalizeOperations(List<String> rawOperations) {
		if (rawOperations == null || rawOperations.isEmpty()) {
			throw new InvalidRegistrationException("INVALID_REGISTRATION", "supportedOperations must not be empty");
		}
		Set<OperationType> operations = new LinkedHashSet<>();
		for (String raw : rawOperations) {
			if (raw == null || raw.isBlank()) {
				throw new InvalidRegistrationException("INVALID_WORKER_CAPABILITY", "supportedOperations contains a blank value");
			}
			String value = raw.trim();
			try {
				operations.add(OperationType.valueOf(value));
			}
			catch (IllegalArgumentException ex) {
				throw new InvalidRegistrationException(
						"INVALID_WORKER_CAPABILITY",
						"unsupported operation type: " + value
				);
			}
		}
		if (operations.isEmpty()) {
			throw new InvalidRegistrationException("INVALID_REGISTRATION", "supportedOperations must not be empty");
		}
		return operations;
	}

	private static Set<String> normalizeCodecs(List<String> rawCodecs) {
		Set<String> codecs = new LinkedHashSet<>();
		if (rawCodecs == null) {
			return codecs;
		}
		for (String raw : rawCodecs) {
			if (raw == null || raw.isBlank()) {
				throw new InvalidRegistrationException("INVALID_WORKER_CAPABILITY", "supportedCodecs contains a blank value");
			}
			String codec = raw.trim().toLowerCase(Locale.ROOT);
			if (!CODEC_PATTERN.matcher(codec).matches()) {
				throw new InvalidRegistrationException("INVALID_WORKER_CAPABILITY", "malformed codec: " + raw.trim());
			}
			if (!CANONICAL_CODECS.contains(codec)) {
				throw new InvalidRegistrationException("INVALID_WORKER_CAPABILITY", "unsupported codec: " + codec);
			}
			codecs.add(codec);
		}
		return codecs;
	}

	private static String normalizeFfmpegVersion(String ffmpegVersion) {
		if (ffmpegVersion == null || ffmpegVersion.isBlank()) {
			return null;
		}
		String trimmed = ffmpegVersion.trim();
		if (trimmed.length() > 64) {
			throw new InvalidRegistrationException("INVALID_REGISTRATION", "ffmpegVersion must be at most 64 characters");
		}
		return trimmed;
	}
}
