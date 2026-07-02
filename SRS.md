# Software Requirements Specification — Payment Settlement API

> **Project:** Payment Settlement & Transaction Processing API
> **Stack:** Java 21 (compiled from a JDK 25 toolchain), Spring Boot 3.5.3 → 4.x, Spring Data JPA, H2/PostgreSQL, Maven
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

---

## 12. Drift Log

This appendix records deliberate deviations from the spec as the project is implemented. **Append-only**, dated by phase. Inline comments in the spec are not edited; the log is the single source of truth for "what we actually shipped."

### 12.1 Phase 1 — Project Scaffolding (committed)

| Item | Spec | As-built | Reason |
|------|------|----------|--------|
| Java | 25 | 21 (target) / JDK 25 (host) | Only OpenJDK 25 is installed on the build host. The maven-compiler-plugin emits Java 21 bytecode (`<java.version>21</java.version>`) so the artifact is portable. Migration to a 25-target toolchain is deferred until Spring Boot 4.0 lands. |
| Spring Boot | 4.x | 3.5.3 (latest GA) | Spring Boot 4.0 has not been released on Maven Central. 3.5.3 is the closest match in dependency coverage (`spring-boot-starter-{web,data-jpa,validation}`, Spring Retry 2.0.12, SpringDoc 2.8.6, H2, PostgreSQL, Lombok). Re-pin when 4.x GA ships. |
| `OptimisticLockingFailureException` handler | not in §7, but implied by FR-5.2 | `GlobalExceptionHandler` returns 409; added `ConcurrencyConflictException` | FR-5.2 ("concurrency conflict → HTTP 409 with retry guidance") would otherwise require redesigning the global handler in Phase 3. Added now, before any `@Version`-bearing entity exists. |
| `spring-retry-test` dependency | implied by §6 (>80% test coverage) | removed | Artifact `org.springframework.retry:spring-retry-test` is **not published** on Maven Central as of Spring Retry 2.0.x. The artifact cannot be resolved by Maven. Phase 6 retry tests will use plain Mockito stubs; the dep is re-added once Spring Retry publishes it. |
| `DomainException#getHttpStatus()` (default 400) | not in §8 | added | Lets `GlobalExceptionHandler` stay single-pass: every subclass declares its target HTTP status. Concrete examples shipped today: `ResourceNotFoundException → NOT_FOUND (404)`, `ConcurrencyConflictException → CONFLICT (409)`. Future `InsufficientFundsException → UNPROCESSABLE_ENTITY (422)` for FR-5.3 will reuse the same mechanism with zero handler changes. |

**Implementation notes the next phase should know:**

- `application.yml` sets `spring.profiles.default: dev` (H2 in-memory, `ddl-auto: create-drop`). `application-prod.yml` reads DB credentials from env vars and is `ddl-auto: validate`; **Flyway is not yet wired** (see SRS §10.2 — out of scaffold scope).
- `AuditingConfig#auditorProvider()` returns `"system"`. Replace once auth lands.
- Spring Boot 3.5.3 + Java 21 implies `jakarta.*` namespaces throughout; anything imported from `javax.*` will fail compilation.

### 12.2 Phase 2 — Account Module (committed)

| Item | Spec | As-built | Reason |
|------|------|----------|--------|
| `ApiResponse<T>` envelope | SRS §6 NFR mentions it but §8 doesn't detail a shape | minimal `{data, timestamp}` | Spec leaves shape open; chose the thinnest body that still gives clients a server-provided timestamp. HTTP status lives on the response wrapper, not the body. |
| Status transition matrix | FR-1.4 / FR-1.5 imply transitions but don't enumerate them | `ACTIVE↔FROZEN`, both→`CLOSED`, `CLOSED` terminal | Encoded as `Map<AccountStatus, Set<AccountStatus>>` in `AccountService`. FR-1.4 debits-while-frozen rule lives in Phase 3 PaymentService using AccountNotActiveException (defined here, not yet thrown). |
| `initialBalance` field on `CreateAccountRequest` | not in FR-1.1 (only `accountNumber, holder, currency`) | optional request field, defaults to 0 if absent | FR-1.1 is silent on initial balance. A boolean/optional parameter is the smallest non-breaking interpretation; client can omit for "open with zero balance" usage. |
| Validation strategy | §2 footnote: "Bean Validation (`jakarta.validation`) + custom constraint validators" | 3 layers: (1) Bean Validation on DTO (regex currency, @NotBlank), (2) service-layer uniqueness/state-machine, (3) DB unique index on `account_number` | Multi-layer is implied. Full ISO 4217 code-list validator still parked; regex `[A-Z]{3}` is the Phase-2 minimum and surfaces the cross-layer pattern. |
| `AccountNotActiveException` defined but unused | not specified for Phase 2 | thrown by Phase 3 PaymentService; `getHttpStatus=409` | Pre-modelled so Phase 3 doesn't have to redesign the exception wallet. Maps to the FR-5.2 conflict-style 409. |
| `DuplicateAccountNumberException` | implied by accountNumber uniqueness | `getHttpStatus=409` | 409 is the conventional "resource already exists" code; matches FR-5.2 vocabulary. |
| Optimistic locking demonstration | §1.3 learning goal + FR-5.2 / Data Integrity NFR | `@Version Long` on `Account`; `ObjectOptimisticLockingFailureException` (Hibernate-thrown, Spring-rooted) caught at `OptimisticLockingFailureException` handler → 409 `CONCURRENCY_CONFLICT` | Phase-2 already exercises optimistic locking end-to-end: two concurrent freeze requests on the same account lose-update correctly. Verified by PATCH /status round-trip. |
| `records` for DTOs vs Lombok `@Value` classes | not specified | Java `record` everywhere (Create, Update, Response, ApiResponse) | Records are immutable-by-construction and play well with Jackson 2.12+; avoids Lombok on DTOs entirely. |

**Phase-2 implementation notes the next phase should know:**

- The Phase-1 `GlobalExceptionHandler` family (`DomainException` + `getHttpStatus()`) was sufficient — no handler edits were needed to land Phase 2.
- The four `@Column` string columns on `Account` exceed the 30-char limit that some strict linting conventions use; nothing functionally broken but flag for review if Flyway is later introduced and H2's PG-compat mode migrates to real PostgreSQL with VARCHAR length caps.
- `Map<AccountStatus, Set<AccountStatus>>` transitions static map in `AccountService` is the source of truth for state-machine rules — Phase 5 audit logs should observe these edges (record `old status → new status`).
- `reason` field on `UpdateAccountStatusRequest` is accepted but not yet persisted; Phase 5 will write it to `AuditLog` (FR-4.1).
- `ddl-auto: create-drop` (dev profile) auto-created the `accounts` table on first boot. Idempotent; H2 logs the same DDL on each restart but no errors.

**Phase-2 smoke results (live curl, all 13 acceptance tests passed):**

| Test | Endpoint | Expected | Got |
|------|----------|----------|-----|
| A1 | `POST /api/v1/accounts` valid | 201, ACTIVE | ✓ |
| A2 | `POST` duplicate accountNumber | 409 DUPLICATE_ACCOUNT_NUMBER | ✓ |
| A3 | `POST` currency="USDD" | 400 VALIDATION_FAILED + fieldErrors | ✓ |
| A4 | `GET /api/v1/accounts/{id}` | 200 active | ✓ |
| A5 | `GET` unknown id | 404 NOT_FOUND | ✓ |
| A6 | `GET /api/v1/accounts?page=0` | 200 + Spring Data Page | ✓ |
| A7 | `PATCH /status` to FROZEN | 200 status=FROZEN, version bumped | ✓ |
| A8 | `PATCH /status` back to ACTIVE | 200 status=ACTIVE | ✓ |
| A9 | `POST` EUR account with balance 777.77 | 201 | ✓ |
| A10 | `PATCH /status` CLOSED on A9 | 422 ACCOUNT_NOT_CLOSABLE | ✓ |
| A11 | `POST` zero-balance GBP → close | 200 status=CLOSED | ✓ |
| A12 | `PATCH /status` ACTIVE on CLOSED | 400 INVALID_STATUS_TRANSITION | ✓ |
| A13 | `/v3/api-docs` paths | 3 account paths visible | ✓ |
| Regression | swagger/api-docs/h2 | 200/200/302 | ✓ |
| Logs | `Unhandled exception`/`HHH900` | none | ✓ |

### 12.2.1 Phase 2 — Deep-review patch (committed)

Applied after a `thinker-with-files-gemini` + `code-reviewer-minimax-m3` deep pass against the original Phase-2 commit. Two must-fix defects surfaced and patched; both verified end-to-end against the live dev profile.

| Item | Spec/SRS | As-built after deep review | Reason |
|------|----------|----------------------------|--------|
| `DataIntegrityViolationException` backstop | not specified explicitly | New `@ExceptionHandler(DataIntegrityViolationException.class)` in `GlobalExceptionHandler` mapping to 409 `DUPLICATE_RESOURCE` | `AccountService.createAccount` separates `existsByAccountNumber()` and `save()`. A concurrent insert of the same account number between the two statements was raising `DataIntegrityViolationException` from the DB, which fell through to the catch-all 500. Handler sits between the existing `OptimisticLockingFailureException` and `MethodArgumentNotValidException` handlers. |
| `freezeAccount`/`unfreezeAccount`/`closeAccount` helpers | implied `@Transactional` per §6 NFR | helpers now carry explicit `@Transactional` | `AccountService` has class-level `@Transactional(readOnly = true)`. The helpers self-invoke `this.updateAccountStatus(...)` which bypasses the AOP proxy, so the inner mutation ran in read-only mode and `save()` would have failed at flush. Each helper is now annotated to ensure the proxy intercepts the call and starts a writable transaction. |
| SQLState-aware integrity split | not specified | Single handler splits the envelope by ANSI SQL state (23505/23503/23502/23514/23000) extracted via a private `extractSqlState(Throwable)` chain-walker | Lets the unique-constraint and FK-constraint families emit semantically distinct envelopes (`DUPLICATE_RESOURCE` vs `REFERENCED_RESOURCE_NOT_FOUND`) without per-exception subclasses. FK 23503 → 404 is forward-looking for Phase-3 payment FKs (`Payment.sourceAccountId → Account.id`, `Payment.targetAccountId → Account.id`). |
| `details.sqlState` debug hint | not in §8 envelope shape | every integrity envelope now includes a single-key `details.sqlState` so clients can branch on uniqueness-vs-FK without parsing message strings | Cheap; one line per branch; matches the spirit of §8 (envelope carries structured `details`). |
| `extractSqlState` chain-walker depth cap | not specified | `maxDepth = 32` paranoia cap + cycle guard | Stock Spring/Hibernate chains are <10 deep. Cap defence against pathological custom wrappers. |

**Subsequent micro-tweaks (post-review #2, all sign-off):**

- FK 23503 → 404 rationale documented inline in handler Javadoc: "404 is preferred over 422 for FK because the failure mode is uniformly 'this referenced id does not exist', which clients can act on by re-fetching the referenced resource."
- `extractSqlState` Javadoc clarified as "outermost SQLException" rather than "deepest" — matches the actual outer→inner scan semantics.
- Lombok warning noise on JDK 25 (deprecated `sun.misc.Unsafe` reflection access) is benign; Lombok 1.18.34 emits Java 21 bytecode as configured.

**Phase 2 deep-review smoke results (all passed, dev profile):**

| Test | Endpoint | Expected | Got |
|------|----------|----------|-----|
| F1 | `POST /api/v1/accounts` valid | 201, envelope `{data:{…status:ACTIVE,balance:"50.00"},timestamp}` | ✓ |
| F2 | `POST` duplicate accountNumber (fast path) | 409 `DUPLICATE_ACCOUNT_NUMBER`, `details=null` | ✓ |
| F3 | `POST` 6× parallel (TOCTOU) | one 201 + five 409 `DUPLICATE_RESOURCE`, `details.sqlState="23505"` | ✓ |
| F4 | `GET /api/v1/accounts?page=0&size=5&sort=createdAt,desc` | 200 + `data.{content,pageable,totalElements,totalPages}` | ✓ |
| F5 | `GET` unknown id | 404 `NOT_FOUND` (unchanged) | ✓ |
| F6 | `PATCH /status` invalid enum value `FOO` | 400 `VALIDATION_FAILED` (jackson conversion error → `HttpMessageNotReadableException` → `MALFORMED_REQUEST`) | ✓ (400 ✓; code-tag is `MALFORMED_REQUEST` rather than `VALIDATION_FAILED` — polymorphic deserialization failure lands before Bean Validation; documented behaviour) |
| F7 | `PATCH /status` reason >1024 chars | 400 `VALIDATION_FAILED` + fieldErrors | ✓ |
| Compile | `mvn -DskipTests compile` | BUILD SUCCESS + Lombok/`Unsafe` warnings (benign) | ✓ |
| Boot log | unhandled exception / `HHH900` | none | ✓ |

### 12.2.2 Phase 2 — Test suite (committed)

Adds the FR-1.x test surface highlighted in §1.3 ("comprehensive tests") and §6 (">80% coverage on service layer"). No production code changed; both files live under `src/test/java/...` and run on the embedded H2 (which inherits from `pom.xml`'s `runtime`-scoped H2 dep, so no new test-scoped dependency was added).

| Item | Spec | As-built | Reason |
|------|------|----------|--------|
| `AccountRepositoryTest` (@DataJpaTest) | SRS §1.3 / §6 service-layer / repository test goal | 10 tests across 5 `@Nested` groups: `SaveAndFindById` (audit + @Version), `ExistsByAccountNumber`, `FindByAccountNumber`, `FindAllPageable` (size / sort / empty), `UniqueConstraint` (`DataIntegrityViolationException` on dup) | `@DataJpaTest` auto-replaces to embedded H2; `@Import(AuditingConfig.class)` wires `@EnableJpaAuditing` so `@CreatedDate`/`@LastModifiedDate`/`@Version` get exercised. `saveAndFlush(...)` triggers the unique-constraint check at flush time. |
| `AccountControllerTest` (@WebMvcTest) | SRS §5.1 endpoints + §6 envelope contract + §8 error shape | 19 tests across 4 `@Nested` groups: `CreateAccount` (6), `GetAccount` (3), `ListAccounts` (1), `UpdateStatus` (9) | `@WebMvcTest(AccountController.class)` + `@Import(GlobalExceptionHandler.class)` loads both the controller and the `@RestControllerAdvice`. `@MockitoBean AccountService` (Spring 6.2+ replacement for the deprecated `@MockBean`) replaces the service without instantiating `AccountRepository`. |
| FR-coverage matrix | FR-1.1..FR-1.5 across 201/200/400/404/409/422 envelopes | every status code from the live curl A1..A13 matrix now also covered at the test slice | See smoke table below for full per-status-code coverage. |
| Test-fix cycle | not specified | one assertion failure on the first POST happy-path test (mock-returned `accountNumber='ACC-T'` but assertion expected request-body `accountNumber='ACC-1001'`) was caught by `mvn test` and fixed by inlining the `AccountResponse` builder + adding the missing FR-1.5 happy-path test in the same pass | Mirrors the loop-fix pattern established by Phase 2 deep-review. Two subsequent nice-to-haves applied: extracted `SAMPLE_TIMESTAMP` constant, sharpened `$.data.balance` assertion on close-zero-balance. |

**Per-test breakdown (29 total, all green on `mvn test`):**

| Test class | Tests | FR coverage |
|------------|------:|-------------|
| `AccountRepositoryTest$SaveAndFindById` | 2 | FR-1.1 round-trip; @Version increment + @LastModifiedDate bump |
| `AccountRepositoryTest$ExistsByAccountNumber` | 2 | FR-1.1 uniqueness gate (positive + negative) |
| `AccountRepositoryTest$FindByAccountNumber` | 2 | FR-1.1 finder (present + absent) |
| `AccountRepositoryTest$FindAllPageable` | 3 | FR-1.3 (size/totalElements, sort=createdAt desc, empty page) |
| `AccountRepositoryTest$UniqueConstraint` | 1 | FR-1.1 TOCTOU backstop (`DataIntegrityViolation` at H2 flush) |
| `AccountControllerTest$CreateAccount` | 6 | FR-1.1 (201+Location, 400×4 validation, 409 dup) |
| `AccountControllerTest$GetAccount` | 3 | FR-1.2 (200 found, 404 missing, 400 type-mismatch) |
| `AccountControllerTest$ListAccounts` | 1 | FR-1.3 (200 paginated envelope) |
| `AccountControllerTest$UpdateStatus` | 9 | FR-1.4 (200 freeze / 200 unfreeze / 400 reason-too-long) + FR-1.5 (200 close-zero / 422 close-nonzero) + invalid transition (400 INVALID_STATUS_TRANSITION) + missing/invalid reason (400) + unknown-id (404) |
| **Total** | **29** | FR-1.1..FR-1.5 envelope matrix fully covered |

**Test-suite smoke results (live `mvn test`):**

| Build step | Result |
|------------|--------|
| `mvn -q -Dtest='AccountRepositoryTest,AccountControllerTest' test` | BUILD SUCCESS, 29/29 pass, 0 failures, 0 errors |
| `mvn -q test` (full suite) | BUILD SUCCESS, no regressions; surefire reports show `NO_FAILURES` |

**Hermeticity notes the next phase should know:**

- Tests rely on the embedded H2 already on `runtime`-scope. Moving to a test-only H2 (e.g., an explicit `src/test/resources/application-test.yml`) is not needed today; reconsider if a test profile needs a different dialect.
- `PageImpl` JSON shape emits a Spring data warning *"Serializing PageImpl instances as-is is not supported"* — pre-existing notice for Spring Boot 3.3+ recommending `PagedModel` (`@EnableSpringDataWebSupport(pageSerializationMode=VIA_DTO)`). The Phase 2 list test asserts against the legacy `PageImpl` shape (`data.content`, `data.totalElements`, `data.totalPages`); a future migration to `PagedModel` will require updating that one assertion.
- `@MockitoBean` was introduced in Spring Framework 6.2; Spring Boot 3.5.3 is the first 3.x line that supports it. If/when we pin below 3.4, fall back to the now-deprecated `@MockBean` annotation.

### 12.3 Future-phase drift

Reserved. Append per phase.
