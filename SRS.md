# Software Requirements Specification — Payment Settlement API

> **Project:** Payment Settlement & Transaction Processing API
> **Stack:** Java 25, Spring Boot 4.x, Spring Data JPA, H2/PostgreSQL, Maven
> **Version:** 1.0

---

## 1. Introduction

### 1.1 Purpose
Build a RESTful API that models a **payment settlement system** — the core backend logic found in every fintech platform. The system manages accounts, processes payments with idempotency guarantees, settles batches asynchronously, and maintains a complete audit trail.

### 1.2 Scope
The API covers:
- Account management (create, hold, close)
- Payment submission with idempotency keys
- Asynchronous batch settlement
- Transaction history & audit logging
- Scheduled reconciliation reports
- Error handling with retry semantics

### 1.3 Goals (Learning Objectives)
- Demonstrate **`@Transactional`** semantics (propagation, isolation, rollback)
- Implement **idempotency** at the API layer (critical for payments)
- Use **Spring Events & `@Async`** for non-blocking settlement
- Apply **`@Scheduled`** for recurring batch jobs
- Configure **Spring Retry / Resilience4j** for transient failures
- Use **Spring Data JPA Auditing** (`@CreatedDate`, `@LastModifiedBy`) for compliance
- Implement **optimistic locking** (`@Version`) to prevent race conditions
- Build a **multi-layered validation** strategy (`@Valid`, custom validators)
- Structure code in **clean layered architecture** (Controller → Service → Repository)
- Write **comprehensive tests** (`@WebMvcTest`, `@DataJpaTest`, integration tests)
- Design **interface-seam architecture** for third-party integrations — mock in dev, real in prod via `@Profile`
- Simulate **external dependencies** (payment gateway, fraud detection, bank linking) with configurable mock clients

---

## 2. System Architecture

```
┌──────────────────────────────────────────────────────┐
│                    HTTP Client                        │
└──────────────────┬───────────────────────────────────┘
                   │ REST (JSON)
┌──────────────────▼───────────────────────────────────┐
│              Controller Layer                         │
│  - AccountController                                  │
│  - PaymentController                                  │
│  - SettlementController                               │
│  - AuditController                                    │
│  - @ControllerAdvice (GlobalExceptionHandler)         │
└──────────────────┬───────────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────────┐
│              Service Layer                            │
│  - AccountService         (@Transactional)            │
│  - PaymentService         (@Transactional)            │
│  - SettlementService      (@Async, @Scheduled)        │
│  - IdempotencyService                                 │
│  - AuditService                                       │
└──────────────────┬───────────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────────┐
│           Integration Layer (Interface Seam)          │
│  ┌──────────────────────────────────────────────┐    │
│  │  <<interface>> PaymentGatewayClient           │    │
│  │  <<interface>> BankAccountClient              │    │
│  │  <<interface>> FraudDetectionClient           │    │
│  │  <<interface>> NotificationClient             │    │
│  └──────────────────────┬───────────────────────┘    │
│                          │                            │
│  ┌──────────────────────▼───────────────────────┐    │
│  │  Mock*Client (@Profile("dev"))               │    │
│  │  or                                           │    │
│  │  Real*Client (@Profile("prod"))              │    │
│  └──────────────────────────────────────────────┘    │
└──────────────────┬───────────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────────┐
│              Repository Layer (Spring Data JPA)       │
│  - AccountRepository                                  │
│  - PaymentRepository                                  │
│  - SettlementRepository                               │
│  - IdempotencyRepository                              │
│  - AuditLogRepository                                 │
└──────────────────┬───────────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────────┐
│              Data Layer (H2 / PostgreSQL)             │
│  - Accounts table                                     │
│  - Payments table                                     │
│  - Settlements table                                  │
│  - Idempotency keys table                             │
│  - Audit log table                                    │
└───────────────────────────────────────────────────────┘
```

### Integration Layer (Mocked by default, swappable to real)

All external dependencies are represented as **Java interfaces** with two implementations:

| Interface | Mock (default, `@Profile("dev")`) | Real (swap via `@Profile("prod")`) |
|-----------|--------------------------------------|--------------------------------------|
| `PaymentGatewayClient` | Simulates card/bank transfers with configurable success rate | Stripe SDK / Adyen SDK |
| `BankAccountClient` | Returns mock account verification data | Plaid SDK / Teller SDK |
| `FraudDetectionClient` | Scores transactions with rule-based simulation | Sift / Forter API |
| `NotificationClient` | Logs notifications to console | SendGrid / Twilio |

**Key design rule:** Services depend on the *interface*, not the implementation. Swapping dev → prod is a single `application-prod.properties` line.

```java
@Service
public class PaymentService {
    private final PaymentGatewayClient gateway;  // ← interface
    private final FraudDetectionClient fraud;     // ← interface

    public PaymentResult process(Payment payment) {
        fraud.score(payment);           // MockSiftClient in dev, RealSiftClient in prod
        return gateway.charge(...);     // MockStripeClient in dev, StripeClient in prod
    }
}
```

### Cross-cutting concerns
- **Auditing:** Spring Data JPA Auditing on all entities
- **Validation:** Bean Validation (`jakarta.validation`) + custom constraint validators
- **Error handling:** `@ControllerAdvice` with structured error responses
- **Retry:** Spring Retry for flaky external integrations
- **Locking:** Optimistic locking via `@Version`
- **API documentation:** OpenAPI / SpringDoc

---

## 3. Data Model

### 3.1 Entity: `Account`

| Field           | Type         | Notes                                         |
|-----------------|--------------|------------------------------------------------|
| id              | UUID (PK)    |                                                |
| accountNumber   | String       | Unique, human-readable                         |
| accountHolder   | String       | Owner name                                     |
| balance         | BigDecimal   | Current balance (18,2 precision)               |
| currency        | String(3)    | ISO 4217 (USD, EUR, GBP)                       |
| status          | Enum         | `ACTIVE`, `FROZEN`, `CLOSED`                   |
| version         | Long         | `@Version` — optimistic lock                   |
| createdAt       | Instant      | `@CreatedDate`                                 |
| updatedAt       | Instant      | `@LastModifiedDate`                            |

### 3.2 Entity: `Payment`

| Field           | Type         | Notes                                         |
|-----------------|--------------|------------------------------------------------|
| id              | UUID (PK)    |                                                |
| idempotencyKey  | String (UQ)  | Client-supplied, prevents duplicates           |
| sourceAccountId | UUID (FK)    | Debit from                                     |
| targetAccountId | UUID (FK)    | Credit to                                      |
| amount          | BigDecimal   | Positive amount                                |
| currency        | String(3)    | ISO 4217                                       |
| status          | Enum         | `PENDING`, `COMPLETED`, `FAILED`, `REVERSED`   |
| failureReason   | String       | Populated on FAILED                            |
| version         | Long         | `@Version`                                     |
| createdAt       | Instant      | `@CreatedDate`                                 |
| processedAt     | Instant      | When settlement occurred                       |

**Constraints:**
- `sourceAccountId != targetAccountId`
- `amount > 0`
- source account must have sufficient balance (checked atomically)
- idempotencyKey unique — replayed requests return original result

### 3.3 Entity: `SettlementBatch`

| Field           | Type         | Notes                                         |
|-----------------|--------------|------------------------------------------------|
| id              | UUID (PK)    |                                                |
| batchDate       | LocalDate    | Date this batch covers                         |
| status          | Enum         | `OPEN`, `PROCESSING`, `SETTLED`, `FAILED`      |
| totalPayments   | int          | Count of payments in batch                     |
| totalAmount     | BigDecimal   | Sum of all payment amounts                     |
| currency        | String(3)    |                                                |
| processedAt     | Instant      | When settlement completed                      |
| createdAt       | Instant      | `@CreatedDate`                                 |

### 3.4 Entity: `IdempotencyRecord`

| Field           | Type         | Notes                                         |
|-----------------|--------------|------------------------------------------------|
| idempotencyKey  | String (PK)  | Client-supplied unique key                     |
| responseStatus  | int          | HTTP status of original response               |
| responseBody    | TEXT/JSON    | Cached response body                           |
| createdAt       | Instant      | TTL-based expiry                               |

### 3.5 Entity: `AuditLog`

| Field           | Type         | Notes                                         |
|-----------------|--------------|------------------------------------------------|
| id              | UUID (PK)    |                                                |
| entityType      | String       | e.g. "Payment", "Account"                      |
| entityId        | UUID         | ID of the affected entity                      |
| action          | String       | e.g. "CREATED", "STATUS_CHANGE", "REVERSAL"    |
| oldValue        | TEXT/JSON    | Previous state (nullable)                      |
| newValue        | TEXT/JSON    | New state                                      |
| performedBy     | String       | Who/what triggered the change                  |
| createdAt       | Instant      | Immutable timestamp                            |

---

## 4. Functional Requirements

### FR-1: Account Management
| ID     | Description                                          |
|--------|------------------------------------------------------|
| FR-1.1 | Create a new account with accountNumber, holder, currency |
| FR-1.2 | Get account by ID (with current balance)             |
| FR-1.3 | List all accounts (paginated)                        |
| FR-1.4 | Freeze/unfreeze an account (disallow debits while frozen) |
| FR-1.5 | Close an account (must have zero balance)            |

### FR-2: Payment Submission & Processing
| ID     | Description                                          |
|--------|------------------------------------------------------|
| FR-2.1 | Submit a payment with idempotencyKey, source, target, amount |
| FR-2.2 | Idempotent replay returns same result (HTTP 200)     |
| FR-2.3 | Validate sufficient funds before debiting            |
| FR-2.4 | Validate accounts are ACTIVE                         |
| FR-2.5 | Prevent self-transfers (source != target)            |
| FR-2.6 | Atomic debit + credit within `@Transactional`        |

### FR-3: Settlement
| ID     | Description                                          |
|--------|------------------------------------------------------|
| FR-3.1 | Automatically create daily settlement batches        |
| FR-3.2 | Process pending payments into an OPEN batch          |
| FR-3.3 | Execute batch settlement asynchronously (`@Async`)   |
| FR-3.4 | Retry failed settlements (Spring Retry)              |
| FR-3.5 | View settlement batch status and summary             |

### FR-4: Audit & Reporting
| ID     | Description                                          |
|--------|------------------------------------------------------|
| FR-4.1 | Log all state-changing operations to AuditLog        |
| FR-4.2 | Query audit trail by entity type and ID              |
| FR-4.3 | Generate daily reconciliation report (`@Scheduled`)  |

### FR-5: Error Handling
| ID     | Description                                          |
|--------|------------------------------------------------------|
| FR-5.1 | Return structured error responses (RFC 7807 style)   |
| FR-5.2 | Concurrency conflict → HTTP 409 with retry guidance  |
| FR-5.3 | Insufficient funds → HTTP 422                        |
| FR-5.4 | Invalid idempotency key → HTTP 400                   |
| FR-5.5 | Validation errors → HTTP 400 with field-level details |

### FR-6: External Integration Mocking
| ID     | Description                                          |
|--------|------------------------------------------------------|
| FR-6.1 | Each external dependency (payment gateway, bank link, fraud, notifications) defined as a Java interface |
| FR-6.2 | Mock implementation provided for each interface — runs fully offline with no API keys |
| FR-6.3 | Mock payment gateway supports configurable success/failure rates to test retry paths |
| FR-6.4 | Mock fraud detection applies configurable rule-based scoring (amount thresholds, velocity checks) |
| FR-6.5 | Mock bank account client returns synthetic account data with configurable failure modes |
| FR-6.6 | Mock notification client logs to console — verifiable in tests |
| FR-6.7 | Real implementations can be swapped in via `@Profile("prod")` without changing service logic |
| FR-6.8 | Integration seam is testable — unit tests wire mocks, integration tests wire mock clients |

---

## 5. API Specification

### 5.1 Accounts

```
POST   /api/v1/accounts                          → Create account
GET    /api/v1/accounts/{id}                     → Get account by ID
GET    /api/v1/accounts                          → List accounts (pageable)
PATCH  /api/v1/accounts/{id}/status              → Freeze/unfreeze/close
```

### 5.2 Payments

```
POST   /api/v1/payments                          → Submit payment (Idempotent-Key header)
GET    /api/v1/payments/{id}                     → Get payment details
GET    /api/v1/payments                          → List payments (pageable, filterable by status)
POST   /api/v1/payments/{id}/reverse             → Reverse a completed payment
```

### 5.3 Settlements

```
GET    /api/v1/settlements                       → List settlement batches
GET    /api/v1/settlements/{id}                  → Get batch details
POST   /api/v1/settlements/{id}/process          → Manually trigger batch processing
```

### 5.4 Audit

```
GET    /api/v1/audit?entityType=Payment&entityId={id}  → Query audit trail
```

### 5.5 Reconciliation Reports

```
GET    /api/v1/reports/daily?date=2026-07-01     → Get daily reconciliation report
```

---

## 6. Non-Functional Requirements

| Requirement        | Detail                                              |
|--------------------|-----------------------------------------------------|
| Idempotency        | Idempotency keys expire after 24h                   |
| Concurrency        | Optimistic locking on balance updates               |
| Data Integrity     | No phantom debits — balance never goes negative      |
| Audit Immutability | AuditLog entries are append-only                    |
| API Consistency    | All responses follow `ApiResponse<T>` wrapper       |
| Performance        | Settlement runs async; API responds <500ms           |
| Test Coverage      | >80% on service layer; integration tests for flows   |

---

## 7. Build Phases

### Phase 1: Project Scaffolding
- Initialize Spring Boot project with Maven
- Configure dependencies (Spring Data JPA, H2, SpringDoc, Validation, Retry)
- Set up layered package structure
- Configure database, auditing, and exception handler

### Phase 2: Account Module
- Account entity, repository, service, controller
- Account CRUD with validation
- Status management (freeze, close)

### Phase 3: Payment Module
- Payment entity, repository, service, controller
- `@Transactional` debit + credit with balance checks
- Idempotency layer (IdempotencyService + filter)
- Optimistic locking for concurrency safety

### Phase 4: Settlement Module
- SettlementBatch entity, repository, service
- `@Async` batch processing
- `@Scheduled` daily batch creation
- Spring Retry for failed settlements

### Phase 5: Audit & Compliance
- AuditLog entity + AOP-based auditing
- Audit query endpoint
- Daily reconciliation report

### Phase 6: Testing & Polish
- Unit tests for services (`@DataJpaTest`, Mockito)
- Integration tests for full payment flow
- API tests (`@WebMvcTest`)
- Mock client tests (verify mock behavior is realistic)
- OpenAPI documentation verification
- Profile switching tests (`dev` vs `prod` config validation)

### Phase 7: Integration Mock Clients
- `PaymentGatewayClient` interface + `MockStripeClient` (simulates 95% success, 5% failure)
- `FraudDetectionClient` interface + `MockSiftClient` (rule-based scoring: amount > 10k flagged, >3 txns/min flagged)
- `BankAccountClient` interface + `MockPlaidClient` (synthetic account data, configurable link failures)
- `NotificationClient` interface + `MockNotificationClient` (console logging with structured output)
- Spring `@Profile("dev")` auto-configuration for all mocks

### Phase 8: Production Integration Showcase
- Demonstrate profile switching: `--spring.profiles.active=prod` behavior
- Show how a real Stripe integration would wire in (documented, not implemented)
- Produce an integration guide document mapping each mock → real API
- End-to-end demo script that exercises all mocked external flows

---

## 8. Error Response Format

All errors follow a consistent structure:

```json
{
  "status": 422,
  "error": "INSUFFICIENT_FUNDS",
  "message": "Account ACC-1001 has insufficient balance. Required: 5000.00, Available: 1200.00",
  "timestamp": "2026-07-02T10:30:00Z",
  "path": "/api/v1/payments",
  "details": {
    "accountId": "acc-1001",
    "required": "5000.00",
    "available": "1200.00"
  }
}
```

On validation errors:
```json
{
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "Validation failed for request body",
  "timestamp": "2026-07-02T10:30:00Z",
  "path": "/api/v1/accounts",
  "fieldErrors": [
    { "field": "accountNumber", "message": "must not be blank" },
    { "field": "currency", "message": "must be a valid ISO 4217 currency code" }
  ]
}
```

---

## 9. Technologies & Dependencies

| Dependency                | Purpose                                    |
|---------------------------|--------------------------------------------|
| Spring Boot 4.x           | Application framework                      |
| Spring Data JPA           | ORM / repository layer                     |
| Spring Web                | REST controllers                           |
| Spring Validation         | Bean Validation (jakarta.validation)       |
| Spring Retry              | Retry transient failures                   |
| SpringDoc OpenAPI         | API documentation (Swagger UI)             |
| H2 Database               | Dev/embedded database                      |
| PostgreSQL Driver         | Production database (optional)             |
| Lombok                    | Boilerplate reduction                      |
| Spring Boot Starter Test  | JUnit 5, Mockito, MockMvc                  |
| Resilience4j (optional)   | Circuit breaker for external calls         |

---

## 10. Production Roadmap

This section maps each mock client to its real-world counterpart and flags additional concerns for production.

### 10.1 Mock → Real API Mapping

| Mock Client | Real API | Purpose | When You'd Need It |
|-------------|----------|---------|--------------------|
| `MockStripeClient` | **Stripe SDK** (`com.stripe:stripe-java`) | Process real card payments, ACH transfers, payouts | Launching with payment processing |
| `MockPlaidClient` | **Plaid SDK** (`com.plaid:plaid-java`) | Link bank accounts, verify identity, check balances | Allowing users to connect external bank accounts |
| `MockSiftClient` | **Sift / Forter / Stripe Radar** | Real-time fraud scoring and chargeback prevention | Processing payments above threshold or in high-risk regions |
| `MockNotificationClient` | **SendGrid / Twilio / SNS** | Email receipts, SMS alerts, webhook delivery | Informing users of payment status changes |

### 10.2 Additional Production Concerns (Out of Scope for Demo)

| Area | What's Needed | Why It Matters |
|------|---------------|----------------|
| **Auth & API Keys** | OAuth2 / JWT auth, API key validation, HMAC request signing | Unauthenticated payment endpoints are a security risk |
| **Secrets Management** | Vault / AWS Secrets Manager / env-specific encrypted config | Hardcoded Stripe keys in `application.properties` is unsafe |
| **Database Migrations** | Flyway or Liquibase for version-controlled schema changes | Manual DDL doesn't scale across environments |
| **Encryption at Rest** | Field-level encryption for PII (account holder names, ssn) | PCI DSS, GDPR, SOC 2 compliance |
| **Rate Limiting** | Bucket4j or Spring Cloud Gateway rate limiting on payment endpoints | Prevents brute-force idempotency key attacks |
| **Observability** | Structured JSON logging (Logstash), Micrometer metrics, distributed tracing (OpenTelemetry) | Debugging production payment failures requires traces |
| **Health Probes** | Spring Actuator liveness/readiness endpoints with dependency checks | Orchestrators (K8s, ECS) need to manage app lifecycle |
| **Disaster Recovery** | Multi-region failover, idempotency key persistence across regions | A single-region outage can't drop payment idempotency guarantees |

### 10.3 Migration Path: Demo → Production

```
Phase 7 (Mock)           ──→   Production Step 1        ──→   Production Step 2
─────────────────────────────────────────────────────────────────────────────
MockStripeClient         ──→   StripeClient (test mode)   ──→   StripeClient (live mode)
                                                                  + PCI attestation
MockPlaidClient          ──→   PlaidClient (sandbox)      ──→   PlaidClient (production)
                                                                  + KYC flow
H2 in-memory DB          ──→   PostgreSQL (dev)           ──→   PostgreSQL (prod)
                                                                  + Flyway migrations
application.properties   ──→   application-prod.properties ──→   Vault + encrypted config
```

---

## 11. Package Structure

```
com.fintech.payment
├── PaymentSettlementApplication.java
│
├── config/
│   ├── AuditingConfig.java          ← @EnableJpaAuditing
│   ├── AsyncConfig.java             ← @EnableAsync
│   ├── RetryConfig.java             ← @EnableRetry
│   └── OpenApiConfig.java           ← SpringDoc
│
├── controller/
│   ├── AccountController.java
│   ├── PaymentController.java
│   ├── SettlementController.java
│   └── AuditController.java
│
├── service/
│   ├── AccountService.java
│   ├── PaymentService.java
│   ├── SettlementService.java
│   ├── IdempotencyService.java
│   └── AuditService.java
│
├── repository/
│   ├── AccountRepository.java
│   ├── PaymentRepository.java
│   ├── SettlementRepository.java
│   ├── IdempotencyRepository.java
│   └── AuditLogRepository.java
│
├── model/
│   ├── entity/
│   │   ├── Account.java
│   │   ├── Payment.java
│   │   ├── SettlementBatch.java
│   │   ├── IdempotencyRecord.java
│   │   └── AuditLog.java
│   ├── dto/
│   │   ├── request/
│   │   │   ├── CreateAccountRequest.java
│   │   │   ├── SubmitPaymentRequest.java
│   │   │   └── UpdateAccountStatusRequest.java
│   │   └── response/
│   │       ├── AccountResponse.java
│   │       ├── PaymentResponse.java
│   │       ├── SettlementResponse.java
│   │       └── ApiErrorResponse.java
│   └── enums/
│       ├── AccountStatus.java
│       ├── PaymentStatus.java
│       └── SettlementStatus.java
│
├── exception/
│   ├── InsufficientFundsException.java
│   ├── AccountNotActiveException.java
│   ├── SelfTransferException.java
│   ├── DuplicateIdempotencyKeyException.java
│   └── GlobalExceptionHandler.java   ← @ControllerAdvice
│
├── integration/
│   ├── PaymentGatewayClient.java     ← Interface
│   ├── dev/
│   │   ├── MockStripeClient.java     ← @Profile("dev")
│   │   ├── MockPlaidClient.java
│   │   ├── MockSiftClient.java
│   │   └── MockNotificationClient.java
│   └── prod/  (documented only — not implemented)
│       └── README.md                 ← Integration guide
│
└── reporting/
    └── ReconciliationReportService.java
```

---

*This document will evolve as we build. Each phase's implementation should reference the relevant FR IDs.*
