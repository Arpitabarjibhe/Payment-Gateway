package com.payments.gateway.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountBalanceResponse {
    private String accountId;
    private String accountName;
    private String currency;
    private String accountType;
    private Long totalDebits;
    private Long totalCredits;
    private Long settledBalance; // calculated based on account type
}
