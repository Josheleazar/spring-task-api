package com.fintech.payment.model.enums;

/**
 * Lifecycle of a {@code SettlementBatch} (SRS §3.3 + FR-3.x).
 *
 * <p>Transition rules enforced by the service layer (SettlementWorker):</p>
 * <ul>
 *   <li>{@code OPEN} — initial state at the daily {@code @Scheduled} tick.
 *       No payments have been associated yet.</li>
 *   <li>{@code PROCESSING} — the worker is iterating the unclaimed COMPLETED
 *       payments and stamping {@code Payment.settlementBatchId}. Transient.
 *       Lives only during the @Async execution window.</li>
 *   <li>{@code SETTLED} — terminal happy state. {@code totalPayments} +
 *       {@code totalAmount} are computed and persisted, {@code processedAt}
 *       is stamped.</li>
 *   <li>{@code FAILED} — terminal failure. Set when the retry budget is
 *       exhausted (the @Retryable's {@code maxAttempts} is exceeded) or when
 *       an unrecoverable error surfaces — typically a constraint violation
 *       during the aggregate-update phase. {@code processedAt} is stamped
 *       with the failure mint so reconciliation reports can pin it.</li>
 * </ul>
 *
 * <p>Re-SETTLING a SETTLED or FAILED batch is intentionally disallowed:
 * clients POST {@code /api/v1/settlements/{id}/process} only on a batch in
 * OPEN or PROCESSING state. The service throws {@code BatchInInvalidStateException}
 * otherwise.</p>
 */
public enum SettlementStatus {
    OPEN,
    PROCESSING,
    SETTLED,
    FAILED
}
