package com.payments.gateway.repository;

import com.payments.gateway.model.PaymentIntent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, String> {
    List<PaymentIntent> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentIntent p WHERE p.id = :id AND p.merchantId = :merchantId")
    Optional<PaymentIntent> findByIdAndMerchantIdForUpdate(@Param("id") String id, @Param("merchantId") String merchantId);
}
