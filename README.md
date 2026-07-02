# Payment Settlement API

RESTful API modeling a payment-settlement backend (accounts, idempotent payments,
async batch settlement, audit log). Full requirements live in
[`SRS.md`](./SRS.md).

## Status

| Phase | Description                              | State      |
|-------|------------------------------------------|------------|
| 1     | Project scaffolding                       | ✅ Done    |
| 2     | Account module                            | ⏳ Pending |
| 3     | Payment module + idempotency              | ⏳ Pending |
| 4     | Settlement (`@Async`, `@Scheduled`, retry)| ⏳ Pending |
| 5     | Audit & reconciliation reports           | ⏳ Pending |
| 6     | Testing & polish                          | ⏳ Pending |
| 7     | Integration mock clients                  | ⏳ Pending |
| 8     | Production integration showcase           | ⏳ Pending |

## Stack

- Java 21 (cross-compiled from JDK 25)
- Spring Boot 3.5.3
- Spring Data JPA, Spring Validation, Spring Retry
- SpringDoc OpenAPI 2.x (Swagger UI)
- H2 (dev) / PostgreSQL (prod)
- Maven 3.9+

## Build & Run

```bash
# compile
mvn compile

# tests
mvn test

# run in dev profile (H2)
mvn spring-boot:run

# open Swagger UI
open http://localhost:8080/swagger-ui.html

# open H2 console (dev only)
open http://localhost:8080/h2-console
```

## Project Layout

```
src/main/java/com/fintech/payment/
├── PaymentSettlementApplication.java   — entry point (@SpringBootApplication)
├── config/
│   ├── AuditingConfig.java             — @EnableJpaAuditing
│   ├── AsyncConfig.java                — @EnableAsync + dedicated executor
│   ├── RetryConfig.java                — @EnableRetry
│   └── OpenApiConfig.java              — SpringDoc metadata
└── exception/
    ├── ApiErrorResponse.java           — RFC-7807-ish error envelope
    ├── DomainException.java            — base for all domain exceptions
    ├── ResourceNotFoundException.java  — 404
    └── GlobalExceptionHandler.java     — @RestControllerAdvice

src/main/resources/
├── application.yml                     — shared settings
├── application-dev.yml                 — H2 / create-drop
└── application-prod.yml                — PostgreSQL / validate
```
