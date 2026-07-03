package com.fintech.payment.integration;

/**
 * Phase 7 — bank-account-link attempt outcome.
 *
 * <p>Three orthogonal outcomes cover Phase-7's integration testing
 * surface:</p>
 * <ul>
 *   <li>{@link #LINKED} — happy-path (mock default).</li>
 *   <li>{@link #ALREADY_LINKED} — idempotent re-link (the bank-side
 *       already has a record; Phase-7 KISS treats as success-noop).</li>
 *   <li>{@link #LINK_REQUIRES_REAUTH} — user's bank session expired;
 *       caller surfaces a 409 Conflict envelope and the client triggers
 *       a re-auth flow (out of Phase 7 scope).</li>
 * </ul>
 */
public enum BankAccountLinkResult {
    /** Link succeeded; mock returns synthetic holder-name metadata. */
    LINKED,
    /** Idempotent re-link; treated as success-noop. */
    ALREADY_LINKED,
    /** Link requires user re-authentication at the bank; surface 409. */
    LINK_REQUIRES_REAUTH
}
