-- =============================================================================
-- Demo seed data — 2 Accounts + 1 Payment (referencing both accounts)
-- Loaded by Spring Boot's SQL init AFTER Hibernate `create-drop` builds the
-- tables (application-dev.yml sets `spring.jpa.defer-datasource-initialization`
-- to `true` so this order holds).
--
-- Status enum values match Java enum literals (AccountStatus / PaymentStatus use
-- `@Enumerated(EnumType.STRING)` on the entities):
--   AccountStatus : ACTIVE, FROZEN, CLOSED
--   PaymentStatus : PENDING, COMPLETED, FAILED, REVERSED
--
-- The UUIDs are fixed so curl smoke tests can reliably reference them.
-- =============================================================================

-- Account A (source) — Alice, $1000 USD
INSERT INTO accounts (
    id, account_number, account_holder, balance, currency, status, version, created_at, updated_at
) VALUES (
    'c26baf18-0b5c-4389-9a28-6625442cecc7',
    'ACCT-1001',
    'Alice',
    1000.00,
    'USD',
    'ACTIVE',
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- Account B (target) — Bob, $500 USD
INSERT INTO accounts (
    id, account_number, account_holder, balance, currency, status, version, created_at, updated_at
) VALUES (
    'd6cb4bd6-6953-488f-a316-c1edbd0dd881',
    'ACCT-1002',
    'Bob',
    500.00,
    'USD',
    'ACTIVE',
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- Seed Payment 1 — Alice -> Bob, $150 USD, already COMPLETED so the Phase-5
-- audit trail has a known-good record to inspect via /api/v1/audit.
INSERT INTO payments (
    id, idempotency_key, source_account_id, target_account_id, amount, currency,
    status, failure_reason, version, created_at, updated_at, processed_at, settlement_batch_id
) VALUES (
    'e48a1c97-6a17-48f8-a0e2-8ea5a9d2c1c9',
    'idem-seed-001',
    'c26baf18-0b5c-4389-9a28-6625442cecc7',
    'd6cb4bd6-6953-488f-a316-c1edbd0dd881',
    150.00,
    'USD',
    'COMPLETED',
    NULL,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    NULL
);
