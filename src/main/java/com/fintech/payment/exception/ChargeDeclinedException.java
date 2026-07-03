package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 7 §12.7.3 — hard 4xx-class payment-gateway decline.
 *
 * <p>Raised when the gateway rejects a charge on a business decision
 * (insufficient issuer funds, fraud-block at the gateway side,
 * 3DS-authentication failure). NOT retried — the user's intent has
 * failed in a way deterministic at this attempt, retrying would
 * repeat the failure.</p>
 *
 * <p>HTTP envelope: 402 Payment Required per RFC-9110 / RFC-7231
 * borrowing pattern; the {@code errorCode} is stable
 * {@code CHARGE_DECLINED} for client-side branching.</p>
 */
public class ChargeDeclinedException extends DomainException {

    public ChargeDeclinedException(String declineReason) {
        super("CHARGE_DECLINED",
                "Payment gateway declined the charge: " + declineReason);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.PAYMENT_REQUIRED;
    }
}
