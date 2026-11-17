package com.payments.gateway.service;

import com.payments.gateway.dto.AccountBalanceResponse;
import com.payments.gateway.model.*;
import com.payments.gateway.repository.LedgerAccountRepository;
import com.payments.gateway.repository.LedgerEntryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerAccountUpsertService ledgerAccountUpsertService;

    public static final String DEFAULT_MERCHANT = "merch_default";
    public static final String DEFAULT_CURRENCY = "usd";

    @PostConstruct
    public void initSystemAccounts() {
        getOrCreateCustomerCashAccount(DEFAULT_CURRENCY);
        getOrCreateMerchantPendingAccount(DEFAULT_MERCHANT, DEFAULT_CURRENCY);
        getOrCreatePlatformFeesAccount(DEFAULT_CURRENCY);
    }

    public String getOrCreateCustomerCashAccount(String currency) {
        String cur = currency != null ? currency.toLowerCase() : DEFAULT_CURRENCY;
        String id = "acc_customer_cash_" + cur;
        createAccountIfAbsent(id, null, "Customer Funds in Transit (" + cur.toUpperCase() + ")", cur, AccountType.ASSET);
        return id;
    }

    public String getOrCreateMerchantPendingAccount(String merchantId, String currency) {
        String mId = merchantId != null ? merchantId : DEFAULT_MERCHANT;
        String cur = currency != null ? currency.toLowerCase() : DEFAULT_CURRENCY;
        String id = "acc_merchant_" + mId + "_" + cur;
        createAccountIfAbsent(id, mId, "Merchant Pending Settlement (" + cur.toUpperCase() + ")", cur, AccountType.LIABILITY);
        return id;
    }

    public String getOrCreatePlatformFeesAccount(String currency) {
        String cur = currency != null ? currency.toLowerCase() : DEFAULT_CURRENCY;
        String id = "acc_platform_fees_" + cur;
        createAccountIfAbsent(id, null, "Platform Fees (" + cur.toUpperCase() + ")", cur, AccountType.REVENUE);
        return id;
    }

    private void createAccountIfAbsent(String id, String merchantId, String name, String currency, AccountType type) {
        if (!accountRepository.existsById(id)) {
            try {
                ledgerAccountUpsertService.tryInsert(LedgerAccount.builder()
                        .id(id)
                        .merchantId(merchantId)
                        .name(name)
                        .currency(currency.toLowerCase())
                        .type(type)
                        .createdAt(Instant.now())
                        .build());
            } catch (Exception ex) {
                log.debug("Concurrent account creation race for account {}: {}", id, ex.getMessage());
            }
        }
    }

    /**
     * Atomically records an immutable double-entry journal entry for a payment.
     * Invariant: Total Debits == Total Credits.
     * Fee Policy: A standard 2% processing fee is collected into the platform revenue account.
     */
    @Transactional
    public void recordPaymentSettlement(String transactionId, Long grossAmount, String currency, String merchantId) {
        String cur = currency != null ? currency.toLowerCase() : DEFAULT_CURRENCY;
        String mId = merchantId != null ? merchantId : DEFAULT_MERCHANT;

        long platformFee = Math.round(grossAmount * 0.02); // 2% gateway processing fee
        long netMerchantAmount = grossAmount - platformFee;

        String cashAccountId = getOrCreateCustomerCashAccount(cur);
        String merchantAccountId = getOrCreateMerchantPendingAccount(mId, cur);
        String feesAccountId = getOrCreatePlatformFeesAccount(cur);

        // Entry 1: DEBIT Asset account (Customer cash in transit)
        LedgerEntry debitCash = LedgerEntry.builder()
                .id("ent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .transactionId(transactionId)
                .accountId(cashAccountId)
                .direction(Direction.DEBIT)
                .amount(grossAmount)
                .createdAt(Instant.now())
                .build();

        // Entry 2: CREDIT Liability account (Merchant pending balance)
        LedgerEntry creditMerchant = LedgerEntry.builder()
                .id("ent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .transactionId(transactionId)
                .accountId(merchantAccountId)
                .direction(Direction.CREDIT)
                .amount(netMerchantAmount)
                .createdAt(Instant.now())
                .build();

        // Entry 3: CREDIT Revenue account (Platform fee)
        LedgerEntry creditFee = LedgerEntry.builder()
                .id("ent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .transactionId(transactionId)
                .accountId(feesAccountId)
                .direction(Direction.CREDIT)
                .amount(platformFee)
                .createdAt(Instant.now())
                .build();

        // Strict Invariant Verification
        long totalDebits = debitCash.getAmount();
        long totalCredits = creditMerchant.getAmount() + creditFee.getAmount();

        if (totalDebits != totalCredits) {
            throw new IllegalStateException(String.format(
                    "Ledger invariant violation: Debits (%d) != Credits (%d)", totalDebits, totalCredits));
        }

        entryRepository.saveAll(List.of(debitCash, creditMerchant, creditFee));
        log.info("Settled ledger entries for transaction {}: Gross={}, Net={}, Fee={}, Currency={}, Merchant={}",
                transactionId, grossAmount, netMerchantAmount, platformFee, cur, mId);
    }

    /**
     * Atomically records reverse entries for a refund.
     * Note on Fee Retention Policy: The full refund amount is debited from merchant pending
     * and credited back to customer cash. Platform fee is retained (standard merchant acquiring model).
     */
    @Transactional
    public void recordRefundSettlement(String refundId, Long refundAmount, String currency, String merchantId) {
        String cur = currency != null ? currency.toLowerCase() : DEFAULT_CURRENCY;
        String mId = merchantId != null ? merchantId : DEFAULT_MERCHANT;

        String cashAccountId = getOrCreateCustomerCashAccount(cur);
        String merchantAccountId = getOrCreateMerchantPendingAccount(mId, cur);

        LedgerEntry debitMerchant = LedgerEntry.builder()
                .id("ent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .transactionId(refundId)
                .accountId(merchantAccountId)
                .direction(Direction.DEBIT)
                .amount(refundAmount)
                .createdAt(Instant.now())
                .build();

        LedgerEntry creditCash = LedgerEntry.builder()
                .id("ent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .transactionId(refundId)
                .accountId(cashAccountId)
                .direction(Direction.CREDIT)
                .amount(refundAmount)
                .createdAt(Instant.now())
                .build();

        if (!debitMerchant.getAmount().equals(creditCash.getAmount())) {
            throw new IllegalStateException("Refund ledger invariant violation: Debits != Credits");
        }

        entryRepository.saveAll(List.of(debitMerchant, creditCash));
        log.info("Settled refund ledger entries for refund {}: Amount={}, Currency={}, Merchant={}",
                refundId, refundAmount, cur, mId);
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse getAccountBalance(String accountId) {
        LedgerAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        Long totalDebits = entryRepository.sumAmountByAccountAndDirection(accountId, Direction.DEBIT);
        Long totalCredits = entryRepository.sumAmountByAccountAndDirection(accountId, Direction.CREDIT);

        long settledBalance;
        if (account.getType() == AccountType.ASSET || account.getType() == AccountType.EXPENSE) {
            settledBalance = totalDebits - totalCredits;
        } else {
            settledBalance = totalCredits - totalDebits;
        }

        return AccountBalanceResponse.builder()
                .accountId(account.getId())
                .accountName(account.getName())
                .currency(account.getCurrency())
                .accountType(account.getType().name())
                .totalDebits(totalDebits)
                .totalCredits(totalCredits)
                .settledBalance(settledBalance)
                .build();
    }

    @Transactional(readOnly = true)
public long getTotalMerchantPendingBalance(String currency) {

    String cur = currency != null
            ? currency.toLowerCase()
            : DEFAULT_CURRENCY;

    List<LedgerAccount> merchantAccounts =
            accountRepository.findByCurrencyAndTypeAndIdStartingWith(
                    cur,
                    AccountType.LIABILITY,
                    "acc_merchant_"
            );

    long totalBalance = 0;

    for (LedgerAccount account : merchantAccounts) {
        AccountBalanceResponse balance = getAccountBalance(account.getId());
        totalBalance += balance.getSettledBalance();
    }

    return totalBalance;
}

    @Transactional(readOnly = true)
    public List<LedgerEntry> getEntriesForTransaction(String transactionId) {
        return entryRepository.findByTransactionId(transactionId);
    }
}
