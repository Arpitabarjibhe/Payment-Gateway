package com.chronos.gateway.dto;

import jakarta.validation.constraints.Min;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {
    @Min(value = 1, message = "amount must be at least 1")
    private Long amount; // optional: if null, refunds full payment amount
}
