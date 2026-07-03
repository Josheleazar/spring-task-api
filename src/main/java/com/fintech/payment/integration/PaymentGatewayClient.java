package com.fintech.payment.integration;

import com.fintech.payment.model.entity.Payment;

import java.util.List;

/**
 * Phase 7 — payment-gateway seam (SRS §2 / FR-6.1).
 *
 * <p>{@code PaymentGatewayClient} is the application-side interface that
 * abstracts charging a batch of completed {@link Payment} rows through an
 * external payment processor (Stripe / Adyen / etc. in production).
 * Two implementations ship:</p>
 * <ul>
 *   <li>{@link MockStripeClient} — {@code @Profile("dev")} default; simulates
 *       a configurable success-rate + stateful fail-then-succeed mode for
 *       retry verification.</li>
 *   <li>{@code RealStripeClient} — production-shape seam documented in SRS §10.1,
 *       not implemented (out of scope; Stripe SDK + PCI attestation
 *       belong to Phase 8 Production Showcase).</li>
 * </ul>
 *
 * <p>{@link #charge(Payment)} raises {@code ChargeDeclinedException} on a hard
 * decline (4xx-class business decision) and {@code PaymentGatewayTransientException}
 * on a retryable infrastructure fault (timeout, 5xx, network blip). The latter
 * is the new {@code @Retryable(…)} target on {@code SettlementWorker} (see Phase 7
 * §12.7.3 for the expansion rationale). The mock implementation has a
 * stateful "fail N times then succeed" mode so the retry chain is verifiable
 * end-to-end without a real Stripe integration.</p>
 *
 * <p>The interface is intentionally thin — no Authentication, no
 * idempotency-key forwarding at this layer (the application's
 * {@code IdempotencyFilter} is the source of truth for the
 * client-supplied {@code Idempotency-Key}. The gateway may receive its
 * own idempotency token via the payment-row UUID, but Phase-7 KISS
 * keeps the call signature a single {@link Payment} parameter; bulk
 * charge is exposed via {@link #batchCharge(List)} for the settlement
 * worker's claim-and-finalize path.</p>
 */
public interface PaymentGatewayClient {

    /**
     * Single-payment charge. Throws {@code ChargeDeclinedException} on a hard
     * decline; throws {@code PaymentGatewayTransientException} on a retryable
     * fault; returns the gateway-assigned confirmation token on success.
     */
    ChargeResult charge(Payment payment);

    /**
     * Bulk charge for the settlement worker's claim-and-finalize path.
     * Returns one {@link ChargeResult} per input payment, in order. The mock
     * implementation issues each charge sequentially so the test harness can
     * inject transient failures at any index.
     *
     * <p>Phase-7 surface: SDK shape only — the worker does NOT invoke this
     * today (the §12.4.2 deferred-II seam from Phase 4 is documented in §12.7). The
     * {@code @Retryable} expansion on {@code SettlementWorker} takes effect
     * the moment an upstream integration point is added.</p>
     */
    List<ChargeResult> batchCharge(List<Payment> payments);
}
