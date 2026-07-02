package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a write conflict is detected — today by
 * {@code org.springframework.dao.OptimisticLockingFailureException} being
 * re-thrown as a domain exception from services. Mapped to HTTP 409 per
 * SRS FR-5.2.
 */
public class ConcurrencyConflictException extends DomainException {

    public ConcurrencyConflictException(String message) {
        super("CONCURRENCY_CONFLICT", message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.CONFLICT;
    }
}
