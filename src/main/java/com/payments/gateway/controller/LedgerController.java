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

    @GetMapping("/reconciliation")
public ResponseEntity<Map<String, Object>> getReconciliationReport(
        @RequestParam(value = "currency", required = false,
                defaultValue = LedgerService.DEFAULT_CURRENCY) String currency) {

    String cur = currency.toLowerCase();

    // 1. Fetch shared global accounts
    String cashAcc = ledgerService.getOrCreateCustomerCashAccount(cur);
    String feeAcc = ledgerService.getOrCreatePlatformFeesAccount(cur);

    AccountBalanceResponse cash =
            ledgerService.getAccountBalance(cashAcc);

    AccountBalanceResponse fees =
            ledgerService.getAccountBalance(feeAcc);

    // 2. Aggregate pending balances across ALL merchants
    long totalMerchantPending =
            ledgerService.getTotalMerchantPendingBalance(cur);

    // 3. Verify the accounting equation
    long totalAssets = cash.getSettledBalance();

    long totalLiabilitiesAndRevenue =
            totalMerchantPending + fees.getSettledBalance();

    long discrepancy = totalAssets - totalLiabilitiesAndRevenue;

    boolean isBalanced = discrepancy == 0;

    return ResponseEntity.ok(Map.of(
            "is_balanced", isBalanced,
            "scope", "global",
            "currency", cur,
            "assets_customer_cash", totalAssets,
            "liabilities_merchant_pending", totalMerchantPending,
            "revenue_platform_fees", fees.getSettledBalance(),
            "discrepancy", discrepancy
    ));
}
    
}
