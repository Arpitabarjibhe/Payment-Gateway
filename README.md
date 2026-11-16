# Payment Gateway & Transaction Settlement Engine

A deterministic financial transaction processing gateway and double-entry ledger engine built with **Java 21** and **Spring Boot 3**.

Designed from first principles to address core distributed systems challenges in payments: network retry duplication, concurrent refund race conditions, and ledger reconciliation invariants.

**Live demo:** TODO-add-url-after-deploy

---

## 1. What This Project Is About

When moving money over HTTP, network failures are inevitable: a client sends a charge request, the server settles the payment, but the connection drops before the HTTP response reaches the client. If the client retries, a naive backend charges the customer a second time. Similarly, two concurrent refund requests on the same transaction can race and over-refund the original balance.

This project implements:
1. **Atomic Idempotent Execution:** Payment settlement and idempotency record resolution occur within the same database transaction boundary.
2. **Pessimistic Concurrency Control:** Row-level locks (`SELECT ... FOR UPDATE`) serialize competing operations on transaction balances, preventing concurrent over-refunds.
3. **Append-Only Double-Entry Ledger:** Balances are dynamically derived by summing immutable journal entries ($\sum \text{Debits} == \sum \text{Credits}$) rather than mutating in-place counter columns.

---

## 2. Core Features

* **Deterministic Request Fingerprinting:** Generates SHA-256 hashes of incoming request payloads. Replaying an identical payload returns the cached response with `Idempotent-Replayed: true`; reusing a key with altered parameters returns `400 Bad Request`.
* **Multi-Tenant Isolation:** Idempotency keys are scoped by composite constraints `(merchant_id, idempotency_key)`, allowing independent tenants to safely use identical key sequences without collision.
* **Concurrency-Safe Refunds:** Full and partial refunds enforce balance validation under row-level database locks, and natively support idempotency keys.
* **Deterministic Card Network Simulator:** Simulates bank processor responses based on card number suffixes (`4242` approved, `0002` insufficient funds, `0005` card declined, `0001` expired).
* **Interactive Simulation Console:** A single-page dashboard at `http://localhost:8080` to simulate multi-threaded packet storms, test card decline paths, inspect live ledger postings, and audit system reconciliation in real time.

---

## 3. Architecture & Data Flow

```
[ Client / Terminal ]
         │  HTTP POST /v1/payments
         │  Headers: Idempotency-Key, X-Merchant-Id
         ▼
[ Ingress Controller ]
         │
         ├── 1. Compute SHA-256 Payload Hash
         ▼
[ Payment Service (@Transactional Single-Commit Boundary) ]
         │
         ├── 2. Pessimistic Lock Check on (merchant_id, idempotency_key)
         │        ├── If RESOLVED: Return cached receipt immediately
         │        └── If PROCESSING: Throw 409 Conflict
         │
         ├── 3. Card Network Simulation (Deterministic processor rules)
         │
         ├── 4. Double-Entry Ledger Postings:
         │        • DEBIT  acc_customer_cash_{currency}    (Gross)
         │        • CREDIT acc_merchant_{id}_{currency}    (Net)
         │        • CREDIT acc_platform_fees_{currency}    (2% Fee)
         │        • Invariant Check: Sum(Debits) == Sum(Credits)
         │
         └── 5. Resolve Idempotency Record (Status = RESOLVED)
                  │
         [ Single Atomic Database Commit ]
                  │
         (Payment + Ledger Entries + Idempotency Record commit together)
```

---

> **No lease/stale-lock mechanism is needed** because the idempotency record is inserted and resolved in the same transaction as the payment; a crash rolls back both. Concurrent duplicates serialize on the `(merchant_id, idempotency_key)` unique constraint and receive 409.

---

## 4. Design Decisions & Trade-Offs

### A. Single-Transaction Atomicity vs. Separate Lock Transactions
* **Decision:** Payment execution, ledger entry creation, and idempotency status resolution execute within a single `@Transactional` boundary.
* **Why:** In multi-step idempotency designs where the lock is acquired, the payment is committed, and the key is resolved in separate transactions, an application crash between steps leaves the payment committed but the key unresolved (or deleted by an error handler). A client retry then charges the customer again. In this design, either the payment, ledger postings, and resolved key commit together in a single atomic SQL commit, or the entire transaction rolls back.

### B. Pessimistic Row Locking (`SELECT ... FOR UPDATE`) for Refunds
* **Decision:** Refunds acquire a pessimistic write lock on the target `PaymentIntent` row before calculating the unrefunded balance.
* **Trade-off:** Pessimistic locking introduces short transaction lock contention if multiple refunds are issued against the same payment simultaneously. However, payment refunds are low-frequency operations per transaction, and row locking guarantees mathematical safety against concurrent over-refund race conditions.

### C. Integer Cents vs. Floating-Point Numbers
* **Decision:** All monetary amounts are represented as 64-bit integers (`Long amount`) in the smallest currency unit (e.g. 5000 = $50.00).
* **Why:** IEEE-754 floating-point arithmetic introduces precision loss and binary approximation errors (e.g. `0.1 + 0.2 = 0.30000000000000004`), which can cause cumulative ledger discrepancies over millions of transactions.

### D. Derived Balances vs. Mutable In-Place Counters
* **Decision:** Account balances are dynamically computed by aggregating historical journal entries (`Assets = Debits - Credits`; `Liabilities = Credits - Debits`).
* **Trade-off:** Deriving balances on the fly requires aggregate database queries rather than simple single-row lookups. For a high-throughput production ledger, snapshots/checkpoints would be introduced periodically. The benefit is complete auditability and mathematical provability: balances cannot be secretly modified or corrupted by rogue `UPDATE` statements.

---

## 5. Known Limitations & Production Roadmap

To keep this project focused and self-contained, the following areas were deliberately scoped out and would be addressed in a full production migration:

| Area | Current Implementation | Production Target |
| :--- | :--- | :--- |
| **Authentication** | `X-Merchant-Id` header (defaults to `merch_default`) | API Gateway with mTLS and cryptographically signed bearer tokens (`sk_live_...`). |
| **Card Data Handling** | Mock card numbers evaluated locally | Tokenization via client-side SDKs and PCI-DSS Level 1 compliant card vaults. |
| **Database Migrations** | Hibernate schema generation | Versioned, repeatable Flyway or Liquibase SQL migrations. |
| **Ledger Scaling** | Real-time SQL aggregation | Periodic snapshot checkpoints to bound aggregation query times on active accounts. |
| **Asynchronous Webhooks** | Synchronous REST responses | Transactional Outbox pattern with HMAC signatures and exponential retry workers. |

---

## 6. API Reference

### 1. Authorize & Settle a Payment

```bash
POST /v1/payments
Idempotency-Key: pay_req_1001
X-Merchant-Id: merch_default
Content-Type: application/json

{
  "amount": 5000,
  "currency": "usd",
  "card_number": "4242424242424242",
  "confirm": true
}
```

**Response (`201 Created`):**
```json
{
  "id": "pi_8a12f9e420b94321",
  "merchant_id": "merch_default",
  "amount": 5000,
  "currency": "usd",
  "status": "SUCCEEDED",
  "card_last4": "4242",
  "failure_code": null,
  "created_at": "2026-11-16T10:00:00Z",
  "updated_at": "2026-11-16T10:00:00Z"
}
```

*Submitting the exact same request with the same `Idempotency-Key` returns the cached response with the `Idempotent-Replayed: true` HTTP response header without re-executing the charge.*

---

### 2. Issue a Partial or Full Refund

```bash
POST /v1/payments/{payment_id}/refunds
Idempotency-Key: ref_req_2001
X-Merchant-Id: merch_default
Content-Type: application/json

{
  "amount": 2500
}
```

**Response (`201 Created`):**
```json
{
  "id": "re_c981b37a112091ea",
  "payment_intent_id": "pi_8a12f9e420b94321",
  "amount": 2500,
  "currency": "usd",
  "status": "succeeded",
  "created_at": "2026-11-16T10:01:00Z"
}
```

---

### 3. Query Account Balance

```bash
GET /v1/ledger/accounts/acc_merchant_merch_default_usd/balance
```

**Response (`200 OK`):**
```json
{
  "account_id": "acc_merchant_merch_default_usd",
  "account_name": "Merchant Pending Settlement (USD)",
  "currency": "usd",
  "account_type": "LIABILITY",
  "total_debits": 2500,
  "total_credits": 4900,
  "settled_balance": 2400
}
```

---

### 4. System Reconciliation Audit

```bash
GET /v1/ledger/reconciliation?currency=usd&merchant_id=merch_default
```

**Response (`200 OK`):**
```json
{
  "is_balanced": true,
  "currency": "usd",
  "merchant_id": "merch_default",
  "assets_customer_cash": 2500,
  "liabilities_merchant_pending": 2400,
  "revenue_platform_fees": 100,
  "discrepancy": 0
}
```

---

## 7. Deterministic Test Cards

| Card Suffix | Simulated Outcome | Failure Code |
| :--- | :--- | :--- |
| `...4242` | Approved & Settles | `null` |
| `...0002` | Declined | `insufficient_funds` |
| `...0005` | Declined | `card_declined_do_not_honor` |
| `...0001` | Declined | `expired_card` |

---

## 8. Verification & Test Suite

The test suite contains **31 automated tests** covering positive paths, edge cases, and concurrency:

```bash
mvn test
```

### Key Test Cases Verified:
* **`testConcurrentIdempotencyStorm`:** Fires 10 concurrent threads against a single idempotency key simultaneously. Asserts that exactly 1 charge is created and 1 set of ledger postings is recorded.
* **`testConcurrentRefundRaceProtection`:** Fires 2 concurrent threads attempting to refund \$50 each on a \$50 charge. Asserts that pessimistic row locks serialize execution so exactly 1 succeeds and the competing request is rejected with `422 Unprocessable Entity`.
* **`testMultiMerchantIdempotencyIsolation`:** Asserts that Merchant A and Merchant B using identical keys (`shared_key_101`) do not collide and execute independently.
* **`testIdempotencyPayloadConflict`:** Asserts that reusing a key with altered parameters returns `400 Bad Request`.

---

## Deploying

### Required Environment Variables

| Variable | Description | Example |
| :--- | :--- | :--- |
| `DATABASE_URL` | JDBC URL of the PostgreSQL database | `jdbc:postgresql://host:5432/paymentdb` |
| `DATABASE_USERNAME` | Database user | `payment_user` |
| `DATABASE_PASSWORD` | Database password | *(secret)* |
| `SPRING_PROFILES_ACTIVE` | Must be `prod` for production | `prod` |
| `PORT` | (Optional) HTTP port; defaults to `8080` | `8080` |
| `HIBERNATE_DDL_AUTO` | (Optional) Schema management; defaults to `update` | `validate` |

> [!IMPORTANT]
> Database migrations via Flyway are future work. Use `HIBERNATE_DDL_AUTO=update` or `create-drop` for now — do not use `validate` on a fresh database.

**Live demo:** TODO-add-url-after-deploy

---

## 9. Running Locally & Container Deployment

### Local Development (Zero-Config In-Memory Database)
```bash
mvn spring-boot:run
```
Open **`http://localhost:8080`** in your browser to access the simulation console.

### Production Container Deployment (PostgreSQL)
```bash
docker compose up -d --build
```
This builds the multi-stage Alpine container and starts a healthy PostgreSQL 16 instance.
