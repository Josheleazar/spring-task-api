package com.fintech.payment.model.enums;

/**
 * Audit-action vocabulary — Phase 5 vocabulary per SRS §3.5 / FR-4.1.
 *
 * <p>The enum is intentionally stringy and ASCII only so persisted rows
 * stay greppable across H2 dev and Postgres prod. New verbs append here
 * without breaking the {@code @Enumerated(STRING)} column.</p>
 *
 * <p>Phase 6 additions: {@link #BATCH_REJECTED} for the §12.5.2
 * forward-flag item (1) — emitted when the
 * {@code settlementWorkerExecutor} rejects a freshly-created OPEN batch
 * (e.g., queue overflow under burst load). Tracks the gap between a
 * persisted OPEN batch row and a never-dispatched worker call so
 * operations can spot stranded batches.</p>
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
    /**
     * SettlementBatch was created but the {@code settlementWorkerExecutor}
     * rejected dispatch (queue full under burst load). The batch row
     * remains OPEN with no worker activity — recoverable by an operator
     * manual trigger via {@code POST /api/v1/settlements/{id}/process}.
     * See SettlementService.createDailyBatch (Phase 6 §12.6).
     */
    BATCH_REJECTED,
    /** SettlementBatch SETTLED finalize. */
    BATCH_SETTLED,
    /** SettlementBatch FAILED — Spring Retry exhausted. */
    BATCH_FAILED,
    /** @Scheduled reconciliation report computed (FR-4.3 warmed). */
    RECONCILED
}
