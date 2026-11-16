package com.chronos.gateway.service;

import com.chronos.gateway.dto.AccountBalanceResponse;
import com.chronos.gateway.model.*;
import com.chronos.gateway.repository.LedgerAccountRepository;
import com.chronos.gateway.repository.LedgerEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit Tests - Double-Entry Ledger Service with Dynamic Multi-Currency Accounts")
class LedgerServiceTest {

    @Mock
    private LedgerAccountRepository accountRepository;

    @Mock
    private LedgerEntryRepository entryRepository;

    @InjectMocks
    private LedgerService ledgerService;

    private static final String MERCHANT_ID = "merch_123";
    private static final String CURRENCY = "usd";

    @Test
    @DisplayName("Payment settlement writes balanced entries: Debits equal Credits exactly")
    @SuppressWarnings("unchecked")
    void testRecordPaymentSettlementBalanced() {
        String txId = "pi_test_123";
        Long grossAmount = 10000L; // $100.00

        when(accountRepository.existsById(anyString())).thenReturn(true);

        ledgerService.recordPaymentSettlement(txId, grossAmount, CURRENCY, MERCHANT_ID);

        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(entryRepository).saveAll(captor.capture());

        List<LedgerEntry> entries = captor.getValue();
        assertThat(entries).hasSize(3);

        LedgerEntry debitCash = entries.stream()
                .filter(e -> e.getDirection() == Direction.DEBIT)
                .findFirst().orElseThrow();
        assertThat(debitCash.getAccountId()).isEqualTo("acc_customer_cash_usd");
        assertThat(debitCash.getAmount()).isEqualTo(10000L);

        long totalCredits = entries.stream()
                .filter(e -> e.getDirection() == Direction.CREDIT)
                .mapToLong(LedgerEntry::getAmount)
                .sum();

        // Fundamental Double-Entry Invariant
        assertThat(debitCash.getAmount()).isEqualTo(totalCredits);

        // Platform fee 2% = 200 cents, Net merchant = 9800 cents
        LedgerEntry creditMerchant = entries.stream()
                .filter(e -> e.getAccountId().equals("acc_merchant_merch_123_usd"))
                .findFirst().orElseThrow();
        assertThat(creditMerchant.getAmount()).isEqualTo(9800L);

        LedgerEntry creditFee = entries.stream()
                .filter(e -> e.getAccountId().equals("acc_platform_fees_usd"))
                .findFirst().orElseThrow();
        assertThat(creditFee.getAmount()).isEqualTo(200L);
    }

    @Test
    @DisplayName("Refund settlement writes balanced reverse entries")
    @SuppressWarnings("unchecked")
    void testRecordRefundSettlementBalanced() {
        String refundId = "re_test_999";
        Long refundAmount = 3000L; // $30.00

        when(accountRepository.existsById(anyString())).thenReturn(true);

        ledgerService.recordRefundSettlement(refundId, refundAmount, CURRENCY, MERCHANT_ID);

        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(entryRepository).saveAll(captor.capture());

        List<LedgerEntry> entries = captor.getValue();
        assertThat(entries).hasSize(2);

        LedgerEntry debitMerchant = entries.stream()
                .filter(e -> e.getDirection() == Direction.DEBIT)
                .findFirst().orElseThrow();
        assertThat(debitMerchant.getAccountId()).isEqualTo("acc_merchant_merch_123_usd");
        assertThat(debitMerchant.getAmount()).isEqualTo(3000L);

        LedgerEntry creditCash = entries.stream()
                .filter(e -> e.getDirection() == Direction.CREDIT)
                .findFirst().orElseThrow();
        assertThat(creditCash.getAccountId()).isEqualTo("acc_customer_cash_usd");
        assertThat(creditCash.getAmount()).isEqualTo(3000L);

        assertThat(debitMerchant.getAmount()).isEqualTo(creditCash.getAmount());
    }

    @Test
    @DisplayName("Balance derivation: Asset balance equals Debits minus Credits")
    void testAssetAccountBalanceDerivation() {
        String cashAccId = "acc_customer_cash_usd";
        LedgerAccount cashAccount = LedgerAccount.builder()
                .id(cashAccId)
                .name("Cash")
                .currency("usd")
                .type(AccountType.ASSET)
                .build();

        when(accountRepository.findById(cashAccId)).thenReturn(Optional.of(cashAccount));
        when(entryRepository.sumAmountByAccountAndDirection(cashAccId, Direction.DEBIT)).thenReturn(5000L);
        when(entryRepository.sumAmountByAccountAndDirection(cashAccId, Direction.CREDIT)).thenReturn(1000L);

        AccountBalanceResponse balance = ledgerService.getAccountBalance(cashAccId);

        // Assets = Debits - Credits => 5000 - 1000 = 4000
        assertThat(balance.getSettledBalance()).isEqualTo(4000L);
        assertThat(balance.getTotalDebits()).isEqualTo(5000L);
        assertThat(balance.getTotalCredits()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Balance derivation: Liability balance equals Credits minus Debits")
    void testLiabilityAccountBalanceDerivation() {
        String merchantAccId = "acc_merchant_merch_123_usd";
        LedgerAccount merchantAccount = LedgerAccount.builder()
                .id(merchantAccId)
                .name("Merchant Pending")
                .currency("usd")
                .type(AccountType.LIABILITY)
                .build();

        when(accountRepository.findById(merchantAccId)).thenReturn(Optional.of(merchantAccount));
        when(entryRepository.sumAmountByAccountAndDirection(merchantAccId, Direction.DEBIT)).thenReturn(1500L);
        when(entryRepository.sumAmountByAccountAndDirection(merchantAccId, Direction.CREDIT)).thenReturn(9800L);

        AccountBalanceResponse balance = ledgerService.getAccountBalance(merchantAccId);

        // Liabilities = Credits - Debits => 9800 - 1500 = 8300
        assertThat(balance.getSettledBalance()).isEqualTo(8300L);
    }
}
