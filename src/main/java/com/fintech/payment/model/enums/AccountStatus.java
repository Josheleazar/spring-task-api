package com.fintech.payment.model.enums;

/**
 * Lifecycle status of an account (SRS §3.1).
 *
 * <p>State transitions (FR-1.4 / FR-1.5) are enforced by
 * {@link com.fintech.payment.service.AccountService}, not here. The enum
 * itself is deliberately a pure value type so it can be persisted as
 * {@code @Enumerated(EnumType.STRING)} without coupling to JPA.</p>
 */
public enum AccountStatus {

    /** Account is open and can be debited / credited normally. */
    ACTIVE,

    /** Account is open for read and credits but disallows debits (FR-1.4). */
    FROZEN,

    /** Terminal state — no further status changes or transactions accepted (FR-1.5). */
    CLOSED
}
