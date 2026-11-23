# Payment Gateway & Transaction Ledger

[![CI](https://github.com/Arpitabarjibhe/Payment-Gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/Arpitabarjibhe/Payment-Gateway/actions/workflows/ci.yml)

A backend payment processing system built with **Java 21, Spring Boot 3, and PostgreSQL**, featuring idempotent payment execution, concurrency-safe refunds, and an immutable double-entry ledger.

## Features

- **Idempotent Payments:** SHA-256 request fingerprinting, cached response replay, and payload conflict detection.
- **Multi-Merchant Isolation:** Idempotency keys are scoped by merchant to prevent cross-merchant collisions.
- **Concurrency-Safe Processing:** Transactional execution and database row-level locking prevent duplicate payments and concurrent over-refunds.
- **Double-Entry Ledger:** Immutable debit/credit journal entries with automatic balance calculation and reconciliation.
- **Payment Simulation:** Deterministic card simulator for successful and declined transactions.
- **Interactive Dashboard:** Simulate payments, concurrent request bursts, refunds, and inspect ledger entries in real time.

## Tech Stack

| Category | Technologies |
|---|---|
| Backend | Java 21, Spring Boot 3 |
| Database | PostgreSQL 16, H2 |
| Persistence | Spring Data JPA, Hibernate |
| Testing | JUnit 5, Mockito, Testcontainers |
| Build | Maven |
| Deployment | Docker, Docker Compose |
| CI | GitHub Actions |

## Architecture
<img width="785" height="423" alt="image" src="https://github.com/user-attachments/assets/8190926f-65bc-4e9c-944b-e6d1f48cbb07" />

Payment execution, ledger postings, and idempotency resolution are committed within a single database transaction.

## Screenshots

### Payment Dashboard
<img width="1920" height="1063" alt="image" src="https://github.com/user-attachments/assets/9a73328e-0313-4e80-a595-97d56dc71a53" />

### Concurrent Request Simulation
<img width="1920" height="1032" alt="image" src="https://github.com/user-attachments/assets/58b0e975-a405-4feb-9093-882669fea920" />

### Ledger & Reconciliation
<img width="1920" height="1032" alt="image" src="https://github.com/user-attachments/assets/d5d105f5-1bd1-4f39-8e3a-40a67e72c9f7" />

## API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| POST | `/v1/payments` | Create and optionally confirm a payment |
| GET | `/v1/payments` | List merchant payments |
| GET | `/v1/payments/{id}` | Retrieve a payment |
| POST | `/v1/payments/{id}/confirm` | Confirm a payment |
| POST | `/v1/payments/{id}/refunds` | Issue a full refund |
| GET | `/v1/ledger/accounts/{id}/balance` | Retrieve account balance |
| GET | `/v1/ledger/entries?transaction_id={id}` | Inspect ledger entries |
| GET | `/v1/ledger/reconciliation?currency=usd` | Verify global ledger balance |

**Request headers:**
- `Idempotency-Key`: Optional key for safe request retries.
- `X-Merchant-Id`: Merchant identifier (defaults to `merch_default`).

## Running Locally

**Prerequisites:** Java 21 and Maven.

```bash
git clone https://github.com/Arpitabarjibhe/Payment-Gateway.git

cd Payment-Gateway

mvn spring-boot:run
```

Open **http://localhost:8080** to access the interactive dashboard.

The default local configuration uses an in-memory H2 database.

### Docker Deployment

Run the application with PostgreSQL 16:

```bash
docker compose up -d --build
```

The application starts on port `8080`.

## Testing

Run the automated test suite:

```bash
mvn clean test
```

**38 automated test cases** cover:

- Payment creation, settlement, and failure scenarios.
- Idempotency, response replay, and payload conflicts.
- Concurrent payment requests and refund race conditions.
- Double-entry ledger invariants and reconciliation.
- Multi-merchant isolation and transaction rollback.
- PostgreSQL integration and concurrency behavior using Testcontainers.

GitHub Actions automatically runs the test suite on pushes and pull requests to `main` and `master`.

## Design Scope

This project uses simulated card processing and is intended as an engineering demonstration, not a production-ready payment processor. Authentication, real payment network integration, PCI-compliant card handling, and asynchronous webhooks are outside its current scope.

---

**Built with Java, Spring Boot, and PostgreSQL.**
