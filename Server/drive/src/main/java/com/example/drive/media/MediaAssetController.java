package com.example.drive.media;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.drive.media.dto.CreateMediaAssetRequest;
import com.example.drive.media.dto.CreateMediaAssetResponse;
import com.example.drive.media.dto.MediaAssetListResponse;
import com.example.drive.media.dto.MediaAssetResponse;
import com.example.drive.media.dto.UploadUrlResponse;
import com.example.drive.security.CurrentAccount;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/media-assets")
public class MediaAssetController {

	private final MediaAssetService mediaAssetService;
	private final CurrentAccount currentAccount;

	public MediaAssetController(MediaAssetService mediaAssetService, CurrentAccount currentAccount) {
		this.mediaAssetService = mediaAssetService;
		this.currentAccount = currentAccount;
	}

	@PostMapping
	public ResponseEntity<CreateMediaAssetResponse> create(@Valid @RequestBody CreateMediaAssetRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(mediaAssetService.create(request, currentAccount.requireId()));
	}

	@GetMapping
	public MediaAssetListResponse list(
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size,
			@RequestParam(name = "status", required = false) String status
	) {
		return mediaAssetService.list(currentAccount.requireId(), page, size, status);
	}

	@GetMapping("/{id}")
	public MediaAssetResponse get(@PathVariable("id") UUID id) {
		return mediaAssetService.get(id, currentAccount.requireId());
	}

	@PostMapping("/{id}/upload-url")
	public UploadUrlResponse createUploadUrl(@PathVariable("id") UUID id) {
		return mediaAssetService.createUploadUrl(id, currentAccount.requireId());
	}

	@PostMapping("/{id}/complete")
	public MediaAssetResponse complete(@PathVariable("id") UUID id) {
		return mediaAssetService.complete(id, currentAccount.requireId());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable("id") UUID id) {
		mediaAssetService.delete(id, currentAccount.requireId());
	}
}
