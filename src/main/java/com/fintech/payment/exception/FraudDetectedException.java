package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 7 — pre-debit fraud-block.
 *
 * <p>Raised by the {@code FraudDetectionClient.isFraudulent} integration
 * point inside {@code PaymentService.submitPayment} on rule-trip detections
 * (amount-threshold, velocity-window). HTTP envelope: 403 Forbidden —
 * the resource state is technically reachable but the requested
 * operation is forbidden by policy.</p>
 *
 * <p>The audit column {@code oldValue} JSON should carry the rule-trip
 * reason string verbatim so operations can reconstruct why a given
 * request was blocked (the §12.7.1 architectural verdict calls out the
 * reason string as a free-form audit-trail signal).</p>
 */
public class FraudDetectedException extends DomainException {

    public FraudDetectedException(String ruleName, String reason) {
        super("FRAUD_DETECTED",
                "Payment blocked by fraud-detection rule '" + ruleName + "': " + reason);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.FORBIDDEN;
    }
}
