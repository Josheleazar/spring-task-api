package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * Raised by {@link com.fintech.payment.service.PaymentService} when the source
 * account's balance is strictly less than the requested payment amount (FR-2.3).
 *
 * <p>Mapped to HTTP 422 Unprocessable Entity — the request is syntactically
 * valid and the transition itself is allowed, but the resource's current
 * balance prevents the operation. Matches FR-5.3 vocabulary.</p>
 */
public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException(String accountNumber, BigDecimal required, BigDecimal available) {
        super("INSUFFICIENT_FUNDS",
                "Account '%s' has insufficient balance. Required: %s, Available: %s"
                        .formatted(accountNumber, required, available));
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
