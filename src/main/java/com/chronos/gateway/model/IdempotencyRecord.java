package com.chronos.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_keys", uniqueConstraints = {
    @UniqueConstraint(name = "uk_merchant_idempotency_key", columnNames = {"merchantId", "idempotency_key"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String key;

    @Column(nullable = false, length = 64)
    private String merchantId;

    @Column(nullable = false, length = 64)
    private String requestHash; // SHA-256 of request payload

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IdempotencyStatus status;

    private Integer responseCode;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Version
    private Long version; // Optimistic concurrency lock

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onPrePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
