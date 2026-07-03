package com.fintech.payment.integration;

import com.fintech.payment.model.entity.Payment;

/**
 * Phase 7 — fraud-detection seam (SRS §2 / FR-6.4).
 *
 * <p>{@code FraudDetectionClient} pre-debit risk scorer. Phase-7 KISS
 * shape is a single {@code isFraudulent(Payment)} call returning a
 * boolean verdict; the richer {@link FraudScore} record is reserved for
 * a future phase where multiple risk factors (amount, velocity, IP
 * reputation, device fingerprint) need a per-axis decomposition.</p>
 *
 * <p>Mock default (FR-6.4) is configurable for two rule patterns:</p>
 * <ul>
 *   <li><strong>amount-threshold</strong>: any payment with
 *       {@code amount > THRESHOLD} is flagged (THRESHOLD defaults to 10 000).</li>
 *   <li><strong>velocity</strong>: more than {@code LIMIT} payments from
 *       the same source account in the last {@code WINDOW_SECONDS}
 *       seconds is flagged (default 3 / 60s).</li>
 * </ul>
 *
 * <p>Production-side seam is documented in §10.1 ({@code RealSiftClient}
 * or {@code Forter}/{@code Stripe Radar}); not implemented in Phase 7.</p>
 *
 * <p>If a {@link Payment} trips a rule, the caller is expected to surface a
 * {@code FraudDetectedException} (mapped to HTTP 403 Forbidden by
 * {@code GlobalExceptionHandler} via {@code DomainException.getHttpStatus}
 * override). The pre-debit integration point lives in
 * {@code PaymentService.submitPayment} per the §12.7.1 architectural
 * verdict.</p>
 */
public interface FraudDetectionClient {

    /**
     * Synchronous risk verdict. Phase-7 KISS is a single boolean; richer
     * decomposition is a forward-flag.
     *
     * @param payment the payment being scored; the implementation may
     *                inspect amount + sourceAccountId + idempotencyKey
     *                for velocity checks.
     * @return {@link FraudScore} — verdict plus a free-form reason string
     *         for the audit trail. Phase-7 mock returns
     *         {@code FraudScore.clean()} by default.
     */
    FraudScore score(Payment payment);

    /**
     * Convenience accessor — backwards-compatible boolean verdict.
     * Implementations should delegate to {@link #score(Payment)}.
     */
    default boolean isFraudulent(Payment payment) {
        return score(payment).fraudulent();
    }
}
