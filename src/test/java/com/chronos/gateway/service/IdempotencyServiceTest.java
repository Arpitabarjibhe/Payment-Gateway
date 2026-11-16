package com.chronos.gateway.service;

import com.chronos.gateway.exception.ConcurrentRequestException;
import com.chronos.gateway.exception.IdempotencyConflictException;
import com.chronos.gateway.model.IdempotencyRecord;
import com.chronos.gateway.model.IdempotencyStatus;
import com.chronos.gateway.repository.IdempotencyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit Tests - Idempotency Service with Pessimistic Locking")
class IdempotencyServiceTest {

    @Mock
    private IdempotencyRepository idempotencyRepository;

    @InjectMocks
    private IdempotencyService idempotencyService;

    private static final String MERCHANT_ID = "merch_123";
    private static final String KEY = "idemp_abc_456";

    @Test
    @DisplayName("Should generate deterministic SHA-256 hash for identical payloads")
    void testComputePayloadHash() {
        String payload1 = "{\"amount\": 5000, \"currency\": \"usd\"}";
        String payload2 = "{\"amount\": 5000, \"currency\": \"usd\"}";

        String hash1 = idempotencyService.computePayloadHash(payload1);
        String hash2 = idempotencyService.computePayloadHash(payload2);

        assertThat(hash1).isNotNull().hasSize(64);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("Lock acquisition: Saves PROCESSING record for new key")
    void testCheckOrLockNewKey() {
        when(idempotencyRepository.findByKeyAndMerchantIdForUpdate(KEY, MERCHANT_ID)).thenReturn(Optional.empty());

        Optional<IdempotencyRecord> result = idempotencyService.checkOrLock(KEY, MERCHANT_ID, "hash_xyz");

        assertThat(result).isEmpty();
        verify(idempotencyRepository, times(1)).saveAndFlush(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("Replay: Returns cached record when key is already RESOLVED with identical payload")
    void testCheckOrLockResolvedKey() {
        IdempotencyRecord resolvedRecord = IdempotencyRecord.builder()
                .key(KEY)
                .merchantId(MERCHANT_ID)
                .requestHash("hash_matching")
                .status(IdempotencyStatus.RESOLVED)
                .responseCode(201)
                .responseBody("{\"id\": \"pi_123\"}")
                .createdAt(Instant.now())
                .build();

        when(idempotencyRepository.findByKeyAndMerchantIdForUpdate(KEY, MERCHANT_ID)).thenReturn(Optional.of(resolvedRecord));

        Optional<IdempotencyRecord> result = idempotencyService.checkOrLock(KEY, MERCHANT_ID, "hash_matching");

        assertThat(result).isPresent();
        assertThat(result.get().getResponseCode()).isEqualTo(201);
        assertThat(result.get().getResponseBody()).contains("pi_123");
    }

    @Test
    @DisplayName("Negative: Reusing key with different payload throws IdempotencyConflictException")
    void testCheckOrLockPayloadMismatchThrowsException() {
        IdempotencyRecord existingRecord = IdempotencyRecord.builder()
                .key(KEY)
                .merchantId(MERCHANT_ID)
                .requestHash("original_hash")
                .status(IdempotencyStatus.RESOLVED)
                .build();

        when(idempotencyRepository.findByKeyAndMerchantIdForUpdate(KEY, MERCHANT_ID)).thenReturn(Optional.of(existingRecord));

        assertThatThrownBy(() -> idempotencyService.checkOrLock(KEY, MERCHANT_ID, "tampered_hash"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("previously used with a different request payload");
    }

    @Test
    @DisplayName("Negative: Concurrent request with unexpired lease throws ConcurrentRequestException")
    void testCheckOrLockInFlightThrowsConcurrentException() {
        IdempotencyRecord inFlightRecord = IdempotencyRecord.builder()
                .key(KEY)
                .merchantId(MERCHANT_ID)
                .requestHash("hash_same")
                .status(IdempotencyStatus.PROCESSING)
                .build();

        when(idempotencyRepository.findByKeyAndMerchantIdForUpdate(KEY, MERCHANT_ID)).thenReturn(Optional.of(inFlightRecord));

        assertThatThrownBy(() -> idempotencyService.checkOrLock(KEY, MERCHANT_ID, "hash_same"))
                .isInstanceOf(ConcurrentRequestException.class)
                .hasMessageContaining("currently in progress");
    }

    @Test
    @DisplayName("Resolve updates status to RESOLVED with response code and body")
    void testResolveKey() {
        IdempotencyRecord record = IdempotencyRecord.builder()
                .key(KEY)
                .merchantId(MERCHANT_ID)
                .status(IdempotencyStatus.PROCESSING)
                .build();

        when(idempotencyRepository.findByKeyAndMerchantIdForUpdate(KEY, MERCHANT_ID)).thenReturn(Optional.of(record));

        idempotencyService.resolve(KEY, MERCHANT_ID, 201, "{\"status\": \"succeeded\"}");

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.RESOLVED);
        assertThat(record.getResponseCode()).isEqualTo(201);
        assertThat(record.getResponseBody()).isEqualTo("{\"status\": \"succeeded\"}");
        verify(idempotencyRepository).saveAndFlush(record);
    }
}
