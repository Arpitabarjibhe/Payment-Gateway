package com.chronos.gateway.service;

import com.chronos.gateway.exception.ConcurrentRequestException;
import com.chronos.gateway.exception.IdempotencyConflictException;
import com.chronos.gateway.model.IdempotencyRecord;
import com.chronos.gateway.model.IdempotencyStatus;
import com.chronos.gateway.repository.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyRepository idempotencyRepository;
    public static final long LOCK_LEASE_SECONDS = 60;

    public String computePayloadHash(String payload) {
        if (payload == null) {
            payload = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Checks for an existing key using a pessimistic row lock (SELECT ... FOR UPDATE)
     * to eliminate concurrent stale-lease reclamation races.
     * Returns an Optional containing:
     * - A resolved record (replay cached response)
     * - An empty optional (new lock acquired or stale lease successfully reclaimed)
     */
    @Transactional
    public Optional<IdempotencyRecord> checkOrLock(String key, String merchantId, String payloadHash) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }

        // Pessimistic write lock ensures only one competing worker can inspect or reclaim an existing record
        Optional<IdempotencyRecord> existingOpt = idempotencyRepository.findByKeyAndMerchantIdForUpdate(key, merchantId);

        if (existingOpt.isPresent()) {
            IdempotencyRecord record = existingOpt.get();

            // 1. Detect payload tampering
            if (!record.getRequestHash().equals(payloadHash)) {
                throw new IdempotencyConflictException(
                        "Idempotency key '" + key + "' was previously used with a different request payload.");
            }

            // 2. Replay cached response if resolved
            if (record.getStatus() == IdempotencyStatus.RESOLVED) {
                log.info("Replaying cached response for key: {} (merchant: {})", key, merchantId);
                return Optional.of(record);
            }

            // 3. Stale lock recovery with row-level lock: safe from concurrent retry races
            if (Instant.now().isAfter(record.getLockedUntil())) {
                log.warn("Reclaiming stale lock on idempotency key '{}' (lease expired at {})", key, record.getLockedUntil());
                record.setLockedUntil(Instant.now().plusSeconds(LOCK_LEASE_SECONDS));
                idempotencyRepository.saveAndFlush(record);
                return Optional.empty(); // Lock successfully reclaimed
            }

            // 4. In-flight collision: reject concurrent race
            throw new ConcurrentRequestException(
                    "A request with the idempotency key '" + key + "' is currently in progress. Please retry shortly.");
        }

        // Lock new key with lease
        IdempotencyRecord newRecord = IdempotencyRecord.builder()
                .key(key)
                .merchantId(merchantId)
                .requestHash(payloadHash)
                .status(IdempotencyStatus.PROCESSING)
                .createdAt(Instant.now())
                .lockedUntil(Instant.now().plusSeconds(LOCK_LEASE_SECONDS))
                .build();

        try {
            idempotencyRepository.saveAndFlush(newRecord);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent race caught by composite unique constraint (merchantId, key)
            throw new ConcurrentRequestException(
                    "A request with the idempotency key '" + key + "' is currently in progress. Please retry shortly.");
        }

        return Optional.empty();
    }

    /**
     * Resolves the idempotency record. When called inside an enclosing @Transactional method,
     * this resolution commits in the exact same database commit as the payment.
     */
    @Transactional
    public void resolve(String key, String merchantId, int responseCode, String responseBody) {
        if (key == null || key.isBlank()) return;

        idempotencyRepository.findByKeyAndMerchantIdForUpdate(key, merchantId).ifPresent(record -> {
            record.setStatus(IdempotencyStatus.RESOLVED);
            record.setResponseCode(responseCode);
            record.setResponseBody(responseBody);
            idempotencyRepository.saveAndFlush(record);
            log.info("Atomically resolved idempotency key '{}' with HTTP {}", key, responseCode);
        });
    }

    @Transactional
    public void unlockOnError(String key, String merchantId) {
        if (key != null && !key.isBlank()) {
            idempotencyRepository.deleteByKeyAndMerchantId(key, merchantId);
        }
    }
}
