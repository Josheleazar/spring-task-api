package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised by {@link com.fintech.payment.service.PaymentService} when the payment
 * currency does not match the source or target account currency. Phase 3 KISS
 * rule — SRS does not specify cross-currency handling; we require an exact
 * match to keep the Phase-3 liquidity model trivially auditable.
 *
 * <p>Mapped to HTTP 422. The request is well-formed; the resource state simply
 * does not permit the operation.</p>
 */
public class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(String requested, String sourceCurrency, String targetCurrency) {
        super("CURRENCY_MISMATCH",
                "Payment currency '%s' does not match source/target currencies ('%s' / '%s')"
                        .formatted(requested, sourceCurrency, targetCurrency));
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
