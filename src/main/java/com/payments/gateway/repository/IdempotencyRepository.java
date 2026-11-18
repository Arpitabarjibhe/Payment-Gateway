package com.payments.gateway.repository;

import com.payments.gateway.model.IdempotencyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;


@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.key = :key AND r.merchantId = :merchantId")
    Optional<IdempotencyRecord> findByKeyAndMerchantIdForUpdate(
            @Param("key") String key,
            @Param("merchantId") String merchantId
    );

    // New method: normal lookup without acquiring a row lock
    Optional<IdempotencyRecord> findByKeyAndMerchantId(
            String key,
            String merchantId
    );
}

