package com.fintech.payment.model.enums;

/**
 * Audit-action vocabulary — Phase 5 vocabulary per SRS §3.5 / FR-4.1.
 *
 * <p>The enum is intentionally stringy and ASCII only so persisted rows
 * stay greppable across H2 dev and Postgres prod. New verbs (added by
 * Phase 7's {@code PaymentGatewayClient} integration, for example) append
 * here without breaking the {@code @Enumerated(STRING)} column.</p>
 */
public enum AuditAction {
    /** Account or Payment or Settlement row created. */
    CREATED,
    /** Account.status frozen/active toggle; batch lifecycle transitions. */
    STATUS_CHANGE,
    /** Payment REVERSED. */
    REVERSED,
    /** SettlementBatch OPEN creation (the daily @Scheduled tick). */
    BATCH_OPEN,
    /** SettlementBatch SETTLED finalize. */
    BATCH_SETTLED,
    /** SettlementBatch FAILED — Spring Retry exhausted. */
    BATCH_FAILED,
    /** @Scheduled reconciliation report computed (FR-4.3 warmed). */
    RECONCILED
}
