package com.example.drive.job;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.domain.Artifact;
import com.example.drive.job.dto.ArtifactDownloadResponse;
import com.example.drive.job.repository.ArtifactRepository;
import com.example.drive.job.repository.JobRepository;
import com.example.drive.storage.ArtifactAccessProperties;
import com.example.drive.storage.ObjectStoreAccess;
import com.example.drive.storage.S3ObjectLocator;

@Service
public class ArtifactAccessService {

	private static final Logger log = LoggerFactory.getLogger(ArtifactAccessService.class);

	private final JobRepository jobRepository;
	private final ArtifactRepository artifactRepository;
	private final ObjectStoreAccess objectStoreAccess;
	private final ArtifactAccessProperties accessProperties;

	public ArtifactAccessService(
			JobRepository jobRepository,
			ArtifactRepository artifactRepository,
			ObjectStoreAccess objectStoreAccess,
			ArtifactAccessProperties accessProperties
	) {
		this.jobRepository = jobRepository;
		this.artifactRepository = artifactRepository;
		this.objectStoreAccess = objectStoreAccess;
		this.accessProperties = accessProperties;
	}

	@Transactional(readOnly = true)
	public ArtifactDownloadResponse createDownloadUrl(UUID jobId, UUID artifactId, UUID accountId) {
		if (!jobRepository.existsByIdAndAccountId(jobId, accountId)) {
			throw new JobNotFoundException(jobId);
		}
		Artifact artifact = artifactRepository.findByIdAndJobId(artifactId, jobId)
				.orElseThrow(() -> new ArtifactNotFoundException(artifactId));
		S3ObjectLocator locator = S3ObjectLocator.parse(artifact.getObjectUri());
		objectStoreAccess.verifyObjectExists(locator.bucket(), locator.key());
		var signed = objectStoreAccess.presignGet(
				locator.bucket(),
				locator.key(),
				accessProperties.getUrlTtl()
		);
		log.info(
				"event=artifact_download_url artifactId={} jobId={} bucket={} key={} expiresAt={}",
				artifactId,
				jobId,
				locator.bucket(),
				locator.key(),
				signed.expiresAt()
		);
		String fileName = locator.fileName();
		if (fileName.isBlank()) {
			fileName = null;
		}
		return new ArtifactDownloadResponse(
				artifact.getId(),
				signed.url().toString(),
				signed.expiresAt(),
				artifact.getContentType(),
				fileName
		);
	}
}
