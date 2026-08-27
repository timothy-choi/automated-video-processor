package com.example.drive.job;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.drive.support.ControlServiceTest;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControlServiceTest
class JobApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void createJobReturns202AndPersistsQueuedJobAndOperations() throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://media-input/video.mp4",
								  "operations": [
								    {"type": "METADATA"},
								    {"type": "THUMBNAIL"},
								    {"type": "TRANSCODE_1080P"}
								  ],
								  "priority": "HIGH",
								  "deadline": "2099-09-01T12:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.id").isString())
				.andExpect(jsonPath("$.inputUri").value("s3://media-input/video.mp4"))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.priority").value("HIGH"))
				.andExpect(jsonPath("$.deadline").value("2099-09-01T12:00:00Z"))
				.andExpect(jsonPath("$.operations.length()").value(3))
				.andExpect(jsonPath("$.operations[0].type").value("METADATA"))
				.andExpect(jsonPath("$.operations[0].status").value("QUEUED"))
				.andExpect(jsonPath("$.operations[0].order").value(0))
				.andExpect(jsonPath("$.operations[1].type").value("THUMBNAIL"))
				.andExpect(jsonPath("$.operations[1].order").value(1))
				.andExpect(jsonPath("$.operations[2].type").value("TRANSCODE_1080P"))
				.andExpect(jsonPath("$.operations[2].order").value(2))
				.andReturn();

		UUID jobId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));

		Integer jobCount = jdbcTemplate.queryForObject(
				"select count(*) from jobs where id = ? and status = 'QUEUED'",
				Integer.class,
				jobId
		);
		Integer operationCount = jdbcTemplate.queryForObject(
				"select count(*) from operations where job_id = ? and status = 'QUEUED'",
				Integer.class,
				jobId
		);
		assertThat(jobCount).isEqualTo(1);
		assertThat(operationCount).isEqualTo(3);
	}

	@Test
	void getJobReturnsPersistedJob() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/clip.mov",
				  "operations": [{"type": "AUDIO_EXTRACTION"}]
				}
				""");

		mockMvc.perform(get("/jobs/" + jobId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(jobId.toString()))
				.andExpect(jsonPath("$.inputUri").value("s3://media-input/clip.mov"))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.priority").value("NORMAL"))
				.andExpect(jsonPath("$.operations.length()").value(1))
				.andExpect(jsonPath("$.operations[0].type").value("AUDIO_EXTRACTION"))
				.andExpect(jsonPath("$.operations[0].status").value("QUEUED"))
				.andExpect(jsonPath("$.operationCount").value(1))
				.andExpect(jsonPath("$.artifactCount").value(0));
	}

	@Test
	void getOperationsReturnsDeterministicOrder() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/ordered.mp4",
				  "operations": [
				    {"type": "METADATA"},
				    {"type": "H264_TO_AV1"},
				    {"type": "TRANSCODE_1080P"}
				  ]
				}
				""");

		mockMvc.perform(get("/jobs/" + jobId + "/operations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(jobId.toString()))
				.andExpect(jsonPath("$.operations.length()").value(3))
				.andExpect(jsonPath("$.operations[0].type").value("METADATA"))
				.andExpect(jsonPath("$.operations[0].order").value(0))
				.andExpect(jsonPath("$.operations[1].type").value("H264_TO_AV1"))
				.andExpect(jsonPath("$.operations[1].order").value(1))
				.andExpect(jsonPath("$.operations[2].type").value("TRANSCODE_1080P"))
				.andExpect(jsonPath("$.operations[2].order").value(2));
	}

	@Test
	void omittedPriorityDefaultsToNormal() throws Exception {
		mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://media-input/default-priority.mp4",
								  "operations": [{"type": "THUMBNAIL"}]
								}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.priority").value("NORMAL"));
	}

	@Test
	void missingInputUriReturns400() throws Exception {
		mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "operations": [{"type": "THUMBNAIL"}]
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void emptyOperationsReturns400() throws Exception {
		mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://bucket/video.mp4",
								  "operations": []
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void invalidOperationTypeReturns400() throws Exception {
		mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://bucket/video.mp4",
								  "operations": [{"type": "NOT_A_REAL_OPERATION"}]
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"));
	}

	@Test
	void retiredTranscode4kTo1080pReturns400() throws Exception {
		mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://bucket/video.mp4",
								  "operations": [{"type": "TRANSCODE_4K_TO_1080P"}]
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"));
	}

	@Test
	void operationsTypeCheckRejectsRetiredTranscode4k() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/clip.mp4",
				  "operations": [{"type": "METADATA"}]
				}
				""");
		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into operations (id, job_id, operation_type, status, operation_order, created_at, queued_at, updated_at)
				values (?, ?, 'TRANSCODE_4K_TO_1080P', 'QUEUED', 1, now(), now(), now())
				""", UUID.randomUUID(), jobId))
				.hasMessageContaining("operations_type_check");
	}

	@Test
	void pastDeadlineReturns400() throws Exception {
		mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "inputUri": "s3://bucket/video.mp4",
								  "operations": [{"type": "THUMBNAIL"}],
								  "deadline": "2020-01-01T00:00:00Z"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("DEADLINE_IN_THE_PAST"));
	}

	@Test
	void unknownJobReturns404() throws Exception {
		UUID missingId = UUID.randomUUID();

		mockMvc.perform(get("/jobs/" + missingId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

		mockMvc.perform(get("/jobs/" + missingId + "/operations"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

		mockMvc.perform(get("/jobs/" + missingId + "/artifacts"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

		mockMvc.perform(get("/jobs/" + missingId + "/artifacts/" + UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

		mockMvc.perform(post("/jobs/" + missingId + "/artifacts/" + UUID.randomUUID() + "/download-url"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
	}

	@Test
	void getArtifactsReturnsEmptyListWhenNoneExist() throws Exception {
		UUID jobId = createJob("""
				{
				  "inputUri": "s3://media-input/clip.mov",
				  "operations": [{"type": "METADATA"}]
				}
				""");

		mockMvc.perform(get("/jobs/" + jobId + "/artifacts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jobId").value(jobId.toString()))
				.andExpect(jsonPath("$.artifacts.length()").value(0));
	}

	private UUID createJob(String json) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isAccepted())
				.andReturn();
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
	}
}
