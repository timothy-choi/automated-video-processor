package com.example.drive.media;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.job.InvalidJobRequestException;
import com.example.drive.job.repository.JobRepository;
import com.example.drive.media.domain.MediaAsset;
import com.example.drive.media.dto.CreateMediaAssetRequest;
import com.example.drive.media.dto.CreateMediaAssetResponse;
import com.example.drive.media.dto.MediaAssetListResponse;
import com.example.drive.media.dto.MediaAssetResponse;
import com.example.drive.media.dto.UploadUrlResponse;
import com.example.drive.observability.MediaAttributes;
import com.example.drive.observability.MediaSpans;
import com.example.drive.storage.ObjectNotFoundException;
import com.example.drive.storage.ObjectStoreAccess;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

@Service
public class MediaAssetService {

	private static final Logger log = LoggerFactory.getLogger(MediaAssetService.class);
	private static final int MAX_SIZE = 100;

	private final MediaAssetRepository mediaAssetRepository;
	private final JobRepository jobRepository;
	private final ObjectStoreAccess objectStoreAccess;
	private final MediaUploadProperties properties;
	private final Clock clock;
	private final Tracer tracer;

	public MediaAssetService(
			MediaAssetRepository mediaAssetRepository,
			JobRepository jobRepository,
			ObjectStoreAccess objectStoreAccess,
			MediaUploadProperties properties,
			Clock clock,
			Tracer tracer
	) {
		this.mediaAssetRepository = mediaAssetRepository;
		this.jobRepository = jobRepository;
		this.objectStoreAccess = objectStoreAccess;
		this.properties = properties;
		this.clock = clock;
		this.tracer = tracer;
	}

	@Transactional
	public CreateMediaAssetResponse create(CreateMediaAssetRequest request, UUID accountId) {
		Span span = MediaSpans.start(tracer, MediaSpans.MEDIA_ASSET_CREATE);
		try (Scope ignored = span.makeCurrent()) {
			MediaSpans.set(span, MediaAttributes.ACCOUNT_ID, accountId.toString());
			long sizeBytes = request.sizeBytes();
			if (sizeBytes > properties.getMaxBytes()) {
				throw MediaAssetException.uploadTooLarge(HttpStatus.BAD_REQUEST);
			}
			String filename = MediaFilenames.sanitize(request.filename());
			String contentType = MediaContentTypes.normalize(request.contentType());
			UUID id = UUID.randomUUID();
			String objectKey = MediaObjectKeys.sourceKey(accountId, id);
			MediaAsset asset = new MediaAsset(
					id,
					accountId,
					filename,
					contentType,
					sizeBytes,
					properties.getInputBucket(),
					objectKey,
					clock.instant()
			);
			mediaAssetRepository.save(asset);
			MediaSpans.set(span, MediaAttributes.MEDIA_ASSET_ID, id.toString());
			MediaSpans.set(span, MediaAttributes.MEDIA_ASSET_STATUS, asset.getStatus().name());
			log.info(
					"event=media_asset_created assetId={} accountId={} bucket={} key={} sizeBytes={}",
					id,
					accountId,
					asset.getBucket(),
					asset.getObjectKey(),
					sizeBytes
			);
			UploadUrlResponse upload = mintUploadUrl(asset);
			return new CreateMediaAssetResponse(MediaAssetResponse.from(asset), upload);
		}
		catch (RuntimeException ex) {
			MediaSpans.recordError(span, ex);
			throw ex;
		}
		finally {
			span.end();
		}
	}

	@Transactional
	public UploadUrlResponse createUploadUrl(UUID id, UUID accountId) {
		Span span = MediaSpans.start(tracer, MediaSpans.MEDIA_ASSET_PRESIGN_UPLOAD);
		try (Scope ignored = span.makeCurrent()) {
			MediaAsset asset = mediaAssetRepository.findByIdAndAccountIdForUpdate(id, accountId)
					.orElseThrow(() -> MediaAssetException.notFound(id));
			MediaSpans.set(span, MediaAttributes.MEDIA_ASSET_ID, asset.getId().toString());
			MediaSpans.set(span, MediaAttributes.ACCOUNT_ID, accountId.toString());
			MediaSpans.set(span, MediaAttributes.MEDIA_ASSET_STATUS, asset.getStatus().name());
			if (asset.getStatus() == MediaAssetStatus.READY) {
				throw MediaAssetException.alreadyReady(id);
			}
			if (asset.getSizeBytes() != null && asset.getSizeBytes() > properties.getMaxBytes()) {
				throw MediaAssetException.uploadTooLarge(HttpStatus.BAD_REQUEST);
			}
			return mintUploadUrl(asset);
		}
		catch (RuntimeException ex) {
			MediaSpans.recordError(span, ex);
			throw ex;
		}
		finally {
			span.end();
		}
	}

	@Transactional
	public MediaAssetResponse complete(UUID id, UUID accountId) {
		Span span = MediaSpans.start(tracer, MediaSpans.MEDIA_ASSET_COMPLETE);
		try (Scope ignored = span.makeCurrent()) {
			MediaAsset asset = mediaAssetRepository.findByIdAndAccountIdForUpdate(id, accountId)
					.orElseThrow(() -> MediaAssetException.notFound(id));
			MediaSpans.set(span, MediaAttributes.MEDIA_ASSET_ID, asset.getId().toString());
			MediaSpans.set(span, MediaAttributes.ACCOUNT_ID, accountId.toString());
			MediaSpans.set(span, MediaAttributes.MEDIA_ASSET_STATUS, asset.getStatus().name());

			ObjectStoreAccess.ObjectHead head;
			try {
				head = objectStoreAccess.headObject(asset.getBucket(), asset.getObjectKey());
			}
			catch (ObjectNotFoundException ex) {
				throw MediaAssetException.uploadObjectNotFound();
			}

			if (asset.getStatus() == MediaAssetStatus.READY) {
				log.info(
						"event=media_asset_complete_idempotent assetId={} accountId={} bucket={} key={}",
						asset.getId(),
						accountId,
						asset.getBucket(),
						asset.getObjectKey()
				);
				return MediaAssetResponse.from(asset);
			}

			if (head.contentLength() <= 0) {
				throw MediaAssetException.uploadObjectEmpty();
			}
			if (head.contentLength() > properties.getMaxBytes()) {
				try {
					objectStoreAccess.deleteObject(asset.getBucket(), asset.getObjectKey());
				}
				catch (RuntimeException deleteEx) {
					log.warn(
							"event=media_asset_oversize_delete_failed assetId={} bucket={} key={}",
							asset.getId(),
							asset.getBucket(),
							asset.getObjectKey()
					);
				}
				asset.markFailed(head.contentLength(), clock.instant());
				throw MediaAssetException.uploadTooLarge(HttpStatus.CONFLICT);
			}

			asset.markReady(head.contentLength(), head.contentType(), clock.instant());
			log.info(
					"event=media_asset_completed assetId={} accountId={} bucket={} key={} sizeBytes={}",
					asset.getId(),
					accountId,
					asset.getBucket(),
					asset.getObjectKey(),
					head.contentLength()
			);
			MediaSpans.set(span, MediaAttributes.MEDIA_ASSET_STATUS, asset.getStatus().name());
			return MediaAssetResponse.from(asset);
		}
		catch (RuntimeException ex) {
			MediaSpans.recordError(span, ex);
			throw ex;
		}
		finally {
			span.end();
		}
	}

	@Transactional(readOnly = true)
	public MediaAssetResponse get(UUID id, UUID accountId) {
		MediaAsset asset = mediaAssetRepository.findByIdAndAccountId(id, accountId)
				.orElseThrow(() -> MediaAssetException.notFound(id));
		return MediaAssetResponse.from(asset);
	}

	@Transactional(readOnly = true)
	public MediaAssetListResponse list(UUID accountId, int page, int size, String status) {
		if (page < 0) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "page must be >= 0");
		}
		if (size <= 0) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "size must be > 0");
		}
		if (size > MAX_SIZE) {
			throw new InvalidJobRequestException("VALIDATION_FAILED", "size must be <= " + MAX_SIZE);
		}
		MediaAssetStatus parsedStatus = parseStatus(status);
		var pageable = PageRequest.of(
				page,
				size,
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
		);
		Page<MediaAsset> result = parsedStatus == null
				? mediaAssetRepository.findByAccountId(accountId, pageable)
				: mediaAssetRepository.findByAccountIdAndStatus(accountId, parsedStatus, pageable);
		List<MediaAssetResponse> items = result.getContent().stream()
				.map(MediaAssetResponse::summary)
				.toList();
		return new MediaAssetListResponse(
				items,
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages()
		);
	}

	@Transactional
	public void delete(UUID id, UUID accountId) {
		MediaAsset asset = mediaAssetRepository.findByIdAndAccountIdForUpdate(id, accountId)
				.orElseThrow(() -> MediaAssetException.notFound(id));
		if (jobRepository.existsByMediaAssetId(id)) {
			throw MediaAssetException.inUse(id);
		}
		try {
			objectStoreAccess.deleteObject(asset.getBucket(), asset.getObjectKey());
		}
		catch (RuntimeException ex) {
			log.warn(
					"event=media_asset_delete_object_failed assetId={} bucket={} key={}",
					asset.getId(),
					asset.getBucket(),
					asset.getObjectKey()
			);
		}
		mediaAssetRepository.delete(asset);
		log.info(
				"event=media_asset_deleted assetId={} accountId={} bucket={} key={}",
				asset.getId(),
				accountId,
				asset.getBucket(),
				asset.getObjectKey()
		);
	}

	@Transactional(readOnly = true)
	public String requireReadyObjectUri(UUID accountId, UUID mediaAssetId) {
		MediaAsset asset = mediaAssetRepository.findByIdAndAccountId(mediaAssetId, accountId)
				.orElseThrow(() -> MediaAssetException.notFound(mediaAssetId));
		if (asset.getStatus() != MediaAssetStatus.READY) {
			throw MediaAssetException.notReady(mediaAssetId);
		}
		return asset.canonicalObjectUri();
	}

	private UploadUrlResponse mintUploadUrl(MediaAsset asset) {
		ObjectStoreAccess.PresignedPut signed = objectStoreAccess.presignPut(
				asset.getBucket(),
				asset.getObjectKey(),
				asset.getContentType(),
				properties.getUrlTtl()
		);
		log.info(
				"event=media_asset_upload_url assetId={} bucket={} key={} expiresAt={}",
				asset.getId(),
				asset.getBucket(),
				asset.getObjectKey(),
				signed.expiresAt()
		);
		return UploadUrlResponse.put(
				signed.url().toString(),
				signed.expiresAt(),
				asset.getContentType()
		);
	}

	private MediaAssetStatus parseStatus(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return MediaAssetStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidJobRequestException("INVALID_ENUM_VALUE", "Unknown status value: " + raw);
		}
	}
}
