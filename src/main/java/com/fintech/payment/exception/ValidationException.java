package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * ValidationException — Phase 5 utility exception for single-parameter
 * validation failures (e.g., {@code AuditController.entityType} allow-list).
 *
 * <p>Distinct from {@code MethodArgumentNotValidException} (which is
 * raised automatically by Spring when {@code @Valid} bean-validation
 * fails on a request body). This exception is for guard-clause validations
 * on request parameters that would otherwise burn an entire envelope
 * generation.</p>
 *
 * <p>Maps to HTTP 400 via {@code DomainException.getHttpStatus()}.</p>
 */
public class ValidationException extends DomainException {

    public ValidationException(String field, String reason) {
        super("VALIDATION_FAILED",
                "Validation failed for parameter '" + field + "': " + reason);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
