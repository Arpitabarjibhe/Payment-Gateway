package com.payments.gateway.controller;

import com.payments.gateway.dto.AccountBalanceResponse;
import com.payments.gateway.model.LedgerEntry;
import com.payments.gateway.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/ledger")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    @GetMapping("/accounts/{id}/balance")
    public ResponseEntity<AccountBalanceResponse> getAccountBalance(@PathVariable("id") String id) {
        return ResponseEntity.ok(ledgerService.getAccountBalance(id));
    }

    @GetMapping("/entries")
    public ResponseEntity<List<LedgerEntry>> getEntries(
            @RequestParam("transaction_id") String transactionId) {
        return ResponseEntity.ok(ledgerService.getEntriesForTransaction(transactionId));
    }

    /**
     * Ledger Health & Invariant Check.
     * Verifies that total customer funds in transit equal merchant pending + platform fees.
     */
    @GetMapping("/reconciliation")
    public ResponseEntity<Map<String, Object>> getReconciliationReport(
            @RequestParam(value = "merchant_id", required = false, defaultValue = LedgerService.DEFAULT_MERCHANT) String merchantId,
            @RequestParam(value = "currency", required = false, defaultValue = LedgerService.DEFAULT_CURRENCY) String currency) {

        String cashAcc = ledgerService.getOrCreateCustomerCashAccount(currency);
        String merchAcc = ledgerService.getOrCreateMerchantPendingAccount(merchantId, currency);
        String feeAcc = ledgerService.getOrCreatePlatformFeesAccount(currency);

        AccountBalanceResponse cash = ledgerService.getAccountBalance(cashAcc);
        AccountBalanceResponse merchant = ledgerService.getAccountBalance(merchAcc);
        AccountBalanceResponse fees = ledgerService.getAccountBalance(feeAcc);

        long totalAssets = cash.getSettledBalance();
        long totalLiabilitiesAndRevenue = merchant.getSettledBalance() + fees.getSettledBalance();
        boolean isBalanced = (totalAssets == totalLiabilitiesAndRevenue);

        return ResponseEntity.ok(Map.of(
                "is_balanced", isBalanced,
                "currency", currency,
                "merchant_id", merchantId,
                "assets_customer_cash", totalAssets,
                "liabilities_merchant_pending", merchant.getSettledBalance(),
                "revenue_platform_fees", fees.getSettledBalance(),
                "discrepancy", totalAssets - totalLiabilitiesAndRevenue
        ));
    }
}
