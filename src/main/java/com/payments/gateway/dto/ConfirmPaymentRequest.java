package com.payments.gateway.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmPaymentRequest {
    private String cardNumber;
}
