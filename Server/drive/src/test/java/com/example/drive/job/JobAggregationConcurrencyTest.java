package com.example.drive.job;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.job.domain.JobStatus;
import com.example.drive.job.domain.OperationStatus;
import com.example.drive.job.dto.ArtifactCompletionDto;
import com.example.drive.job.dto.CompleteOperationRequest;
import com.example.drive.job.dto.FailOperationRequest;
import com.example.drive.job.dto.MetadataResultDto;
import com.example.drive.job.repository.JobRepository;
import com.example.drive.job.repository.OperationRepository;
import com.example.drive.support.ControlServiceTest;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
class JobAggregationConcurrencyTest {

	private static final String SHA256 =
			"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private InternalOperationService internalOperationService;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private OperationRepository operationRepository;

	@BeforeEach
	void clearTables() {
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from operations");
		jdbcTemplate.update("delete from jobs");
	}

	@Test
	void concurrentCompletionsLeaveJobCompleted() throws Exception {
		for (int i = 0; i < 8; i++) {
			clearTables();
			UUID jobId = createTwoOpJob();
			UUID metadataId = claimByType("METADATA");
			UUID thumbnailId = claimByType("THUMBNAIL");
			runConcurrent(
					() -> internalOperationService.complete(metadataId, metadataComplete()),
					() -> internalOperationService.complete(thumbnailId, thumbnailComplete(jobId, thumbnailId))
			);
			assertThat(operationRepository.findById(metadataId).orElseThrow().getStatus())
					.isEqualTo(OperationStatus.COMPLETED);
			assertThat(operationRepository.findById(thumbnailId).orElseThrow().getStatus())
					.isEqualTo(OperationStatus.COMPLETED);
			assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
					.isEqualTo(JobStatus.COMPLETED);
		}
	}

	@Test
	void concurrentCompleteAndFailLeaveJobFailed() throws Exception {
		UUID jobId = createTwoOpJob();
		UUID metadataId = claimByType("METADATA");
		UUID thumbnailId = claimByType("THUMBNAIL");
		runConcurrent(
				() -> internalOperationService.complete(metadataId, metadataComplete()),
				() -> internalOperationService.fail(thumbnailId, new FailOperationRequest(9L, "thumbnail failed"))
		);
		assertThat(operationRepository.findById(metadataId).orElseThrow().getStatus())
				.isEqualTo(OperationStatus.COMPLETED);
		assertThat(operationRepository.findById(thumbnailId).orElseThrow().getStatus())
				.isEqualTo(OperationStatus.FAILED);
		assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
				.isEqualTo(JobStatus.FAILED);
	}

	private void runConcurrent(Runnable first, Runnable second) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> a = pool.submit(() -> {
				ready.countDown();
				start.await();
				first.run();
				return null;
			});
			Future<?> b = pool.submit(() -> {
				ready.countDown();
				start.await();
				second.run();
				return null;
			});
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			a.get(15, TimeUnit.SECONDS);
			b.get(15, TimeUnit.SECONDS);
		}
	}

	private UUID createTwoOpJob() throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://media-input/concurrent.mp4",
								  "operations": [
								    {"type": "METADATA"},
								    {"type": "THUMBNAIL"}
								  ]
								}
								"""))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}

	private UUID claimByType(String type) {
		var claimed = internalOperationService.claimNextExecutableOperation()
				.orElseThrow(() -> new AssertionError("expected claimable " + type));
		assertThat(claimed.type().name()).isEqualTo(type);
		return claimed.operationId();
	}

	private static CompleteOperationRequest metadataComplete() {
		return new CompleteOperationRequest(
				12L,
				new MetadataResultDto(2.0, "mp4", 100L, "h264", null, 320, 240, 30.0),
				null
		);
	}

	private static CompleteOperationRequest thumbnailComplete(UUID jobId, UUID operationId) {
		return new CompleteOperationRequest(
				20L,
				null,
				new ArtifactCompletionDto(
						"s3://media-output/jobs/" + jobId + "/operations/" + operationId + "/thumbnail.jpg",
						"image/jpeg",
						100L,
						SHA256
				)
		);
	}
}
