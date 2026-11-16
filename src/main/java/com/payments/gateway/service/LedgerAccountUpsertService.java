package com.payments.gateway.service;

import com.payments.gateway.model.LedgerAccount;
import com.payments.gateway.repository.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerAccountUpsertService {

    private final LedgerAccountRepository accountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tryInsert(LedgerAccount account) {
        accountRepository.saveAndFlush(account);
    }
}
