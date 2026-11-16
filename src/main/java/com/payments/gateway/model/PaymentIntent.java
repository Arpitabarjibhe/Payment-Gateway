package com.payments.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "payment_intents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentIntent {

    @Id
    @Column(length = 64)
    private String id; // e.g. pi_...

    @Column(nullable = false, length = 64)
    private String merchantId;

    @Column(nullable = false)
    private Long amount; // in cents (e.g. 2500 = $25.00)

    @Column(nullable = false, length = 3)
    private String currency; // 'usd', 'inr', 'eur'

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(length = 4)
    private String cardLast4;

    @Column(length = 64)
    private String failureCode;

    @Version
    private Long version; // Optimistic concurrency lock

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onPrePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
