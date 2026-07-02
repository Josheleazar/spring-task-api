package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for all domain-level exceptions thrown from the service layer.
 *
 * <p>Each subclass carries:
 * <ul>
 *   <li>a stable machine-readable {@code errorCode} surfaced to API clients via
 *       {@link ApiErrorResponse#error()}, and</li>
 *   <li>a target {@link HttpStatus} surfaced via {@link ApiErrorResponse#status()}
 *       so {@link GlobalExceptionHandler} stays single-pass as new exception
 *       types are introduced in later phases.</li>
 * </ul>
 *
 * <p>The default status is 400 BAD REQUEST. Subclasses with a different status
 * (e.g. {@link com.fintech.payment.exception.ConcurrencyConflictException} →
 * 409, future {@code InsufficientFundsException} → 422) override {@link
 * #getHttpStatus()}.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DomainException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /**
     * HTTP status the {@code @ControllerAdvice} should use when responding to
     * this exception. Defaults to {@link HttpStatus#BAD_REQUEST}.
     */
    public HttpStatus getHttpStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
