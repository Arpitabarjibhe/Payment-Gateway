package com.chronos.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "ledger_entries", indexes = {
    @Index(name = "idx_ledger_transaction", columnList = "transactionId"),
    @Index(name = "idx_ledger_account", columnList = "accountId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {

    @Id
    @Column(length = 64)
    private String id; // e.g. ent_...

    @Column(nullable = false, length = 64)
    private String transactionId; // links to PaymentIntent ID or Refund ID

    @Column(nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction; // DEBIT or CREDIT

    @Column(nullable = false)
    private Long amount; // in cents, strictly positive

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onPrePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
