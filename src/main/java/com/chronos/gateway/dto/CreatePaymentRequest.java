package com.chronos.gateway.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePaymentRequest {

    @NotNull(message = "amount is required")
    @Min(value = 50, message = "amount must be at least 50 (cents)")
    private Long amount;

    @NotBlank(message = "currency is required")
    private String currency; // 'usd', 'inr', 'eur'

    private String cardNumber; // optional at creation; can confirm later or pass confirm=true

    private Boolean confirm; // if true, confirms immediately
}
