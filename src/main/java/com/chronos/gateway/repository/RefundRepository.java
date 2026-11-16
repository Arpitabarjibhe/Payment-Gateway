package com.chronos.gateway.repository;

import com.chronos.gateway.model.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RefundRepository extends JpaRepository<Refund, String> {
    List<Refund> findByPaymentIntentId(String paymentIntentId);
}
