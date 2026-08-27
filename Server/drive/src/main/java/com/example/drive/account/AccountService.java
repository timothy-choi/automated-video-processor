package com.example.drive.account;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.drive.account.domain.Account;
import com.example.drive.account.domain.ApiKey;
import com.example.drive.account.dto.AccountCreatedResponse;
import com.example.drive.account.dto.AccountResponse;
import com.example.drive.account.dto.ApiKeyCreatedResponse;
import com.example.drive.account.dto.ApiKeyListResponse;
import com.example.drive.account.dto.ApiKeyMetadataResponse;
import com.example.drive.account.repository.AccountRepository;
import com.example.drive.account.repository.ApiKeyRepository;
import com.example.drive.security.AccountPrincipal;

@Service
public class AccountService {

	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	private final AccountRepository accountRepository;
	private final ApiKeyRepository apiKeyRepository;
	private final AuthProperties authProperties;
	private final Clock clock;

	public AccountService(
			AccountRepository accountRepository,
			ApiKeyRepository apiKeyRepository,
			AuthProperties authProperties,
			Clock clock
	) {
		this.accountRepository = accountRepository;
		this.apiKeyRepository = apiKeyRepository;
		this.authProperties = authProperties;
		this.clock = clock;
	}

	@Transactional
	public AccountCreatedResponse createAccount(String name) {
		if (!authProperties.isAccountRegistrationEnabled()) {
			throw new AccountRegistrationDisabledException();
		}
		Instant now = clock.instant();
		Account account = accountRepository.save(new Account(UUID.randomUUID(), name.trim(), now));
		IssuedKey issued = issueKey(account, now);
		log.info("event=account_created accountId={} apiKeyId={} prefix={}",
				account.getId(), issued.key().getId(), issued.key().getKeyPrefix());
		return new AccountCreatedResponse(toAccountResponse(account), toCreatedResponse(issued));
	}

	@Transactional
	public ApiKeyCreatedResponse createApiKey(UUID accountId) {
		Account account = accountRepository.findById(accountId)
				.orElseThrow(() -> new IllegalStateException("Authenticated account is missing"));
		IssuedKey issued = issueKey(account, clock.instant());
		log.info("event=api_key_created accountId={} apiKeyId={} prefix={}",
				accountId, issued.key().getId(), issued.key().getKeyPrefix());
		return toCreatedResponse(issued);
	}

	@Transactional(readOnly = true)
	public ApiKeyListResponse listApiKeys(UUID accountId) {
		List<ApiKeyMetadataResponse> items = apiKeyRepository.findByAccount_IdOrderByCreatedAtAsc(accountId)
				.stream()
				.map(key -> new ApiKeyMetadataResponse(
						key.getId(),
						key.getKeyPrefix(),
						key.getCreatedAt(),
						key.getRevokedAt()
				))
				.toList();
		return new ApiKeyListResponse(items);
	}

	@Transactional
	public ApiKeyMetadataResponse revokeApiKey(UUID accountId, UUID apiKeyId) {
		ApiKey key = apiKeyRepository.findByIdAndAccount_Id(apiKeyId, accountId)
				.orElseThrow(() -> new ApiKeyNotFoundException(apiKeyId));
		key.revoke(clock.instant());
		log.info("event=api_key_revoked accountId={} apiKeyId={}", accountId, apiKeyId);
		return new ApiKeyMetadataResponse(key.getId(), key.getKeyPrefix(), key.getCreatedAt(), key.getRevokedAt());
	}

	@Transactional(readOnly = true)
	public Optional<AccountPrincipal> authenticate(String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			return Optional.empty();
		}
		String hash = ApiKeyHasher.sha256Hex(rawKey);
		return apiKeyRepository.findByKeyHashWithAccount(hash)
				.filter(key -> !key.isRevoked())
				.filter(key -> key.getAccount().isActive())
				.map(key -> new AccountPrincipal(key.getAccount().getId(), key.getId()));
	}

	@Transactional
	public void ensureBootstrapKey(UUID accountId, String rawKey) {
		Account account = accountRepository.findById(accountId)
				.orElseThrow(() -> new IllegalStateException("Legacy account is missing; Flyway V17 did not apply"));
		String hash = ApiKeyHasher.sha256Hex(rawKey);
		if (apiKeyRepository.existsByKeyHash(hash)) {
			log.info("event=bootstrap_api_key_present accountId={}", accountId);
			return;
		}
		Instant now = clock.instant();
		ApiKey key = apiKeyRepository.save(new ApiKey(
				UUID.randomUUID(),
				account,
				ApiKeySecrets.displayPrefix(rawKey),
				hash,
				now
		));
		log.info("event=bootstrap_api_key_ensured accountId={} apiKeyId={} prefix={}",
				accountId, key.getId(), key.getKeyPrefix());
	}

	private IssuedKey issueKey(Account account, Instant now) {
		for (int attempt = 0; attempt < 5; attempt++) {
			String raw = ApiKeySecrets.generate();
			String hash = ApiKeyHasher.sha256Hex(raw);
			if (apiKeyRepository.existsByKeyHash(hash)) {
				continue;
			}
			ApiKey key = apiKeyRepository.save(new ApiKey(
					UUID.randomUUID(),
					account,
					ApiKeySecrets.displayPrefix(raw),
					hash,
					now
			));
			return new IssuedKey(key, raw);
		}
		throw new IllegalStateException("Unable to allocate a unique API key");
	}

	private static AccountResponse toAccountResponse(Account account) {
		return new AccountResponse(account.getId(), account.getName(), account.getStatus(), account.getCreatedAt());
	}

	private static ApiKeyCreatedResponse toCreatedResponse(IssuedKey issued) {
		return new ApiKeyCreatedResponse(
				issued.key().getId(),
				issued.rawKey(),
				issued.key().getKeyPrefix(),
				issued.key().getCreatedAt()
		);
	}

	private record IssuedKey(ApiKey key, String rawKey) {
	}
}
