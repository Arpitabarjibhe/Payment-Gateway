package com.chronos.gateway.repository;

import com.chronos.gateway.model.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, String> {
    List<LedgerAccount> findByMerchantId(String merchantId);
}
