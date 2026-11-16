package com.payments.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Refund {

    @Id
    @Column(length = 64)
    private String id; // re_...

    @Column(nullable = false, length = 64)
    private String paymentIntentId;

    @Column(nullable = false)
    private Long amount; // in cents

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 32)
    private String status; // 'succeeded'

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onPrePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
