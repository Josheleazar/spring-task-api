package com.fintech.payment.exception;

/**
 * Phase 7 §12.7.3 — retryable payment-gateway transient fault.
 *
 * <p>Raised when {@code PaymentGatewayClient.charge(Payment)} hits an
 * infrastructure-class retryable failure (timeout, 5xx gateway error,
 * network blip). Distinct from {@link ChargeDeclinedException}, which
 * is a hard business decision (4xx-class decline) and is NOT retried.</p>
 *
 * <p>This class is a plain {@code RuntimeException}, NOT a
 * {@link DomainException}, because the worker-level retry chain never
 * wants it surfaced as an HTTP envelope — the
 * {@code @Retryable(retryFor = PaymentGatewayTransientException.class)}
 * boundary on {@code SettlementWorker.processBatchAsync} catches it
 * and re-runs the failed segment. If the retry budget is exhausted,
 * the worker marks the batch {@code FAILED} (per §12.4 FR-3.4
 * semantics) without ever exposing this exception to the API layer.</p>
 *
 * <p>Phase-7 surfaces:</p>
 * <ul>
 *   <li>SettlementWorker {@code @Retryable} expanded to include this
 *       class in {@code retryFor} (forward-flag — no usage until the
 *       worker actually invokes a gateway).</li>
 *   <li>MockStripeClient stateful "fail N times then succeed" mode
 *       injects this exception via {@code doAnswer(...)} in tests.</li>
 * </ul>
 */
public class PaymentGatewayTransientException extends RuntimeException {

    public PaymentGatewayTransientException(String message) {
        super(message);
    }

    public PaymentGatewayTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
