package com.payments.gateway.repository;

import com.payments.gateway.model.AccountType;
import com.payments.gateway.model.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, String> {

    List<LedgerAccount> findByMerchantId(String merchantId);

    List<LedgerAccount> findByCurrencyAndTypeAndIdStartingWith(
            String currency,
            AccountType type,
            String idPrefix
    );
}