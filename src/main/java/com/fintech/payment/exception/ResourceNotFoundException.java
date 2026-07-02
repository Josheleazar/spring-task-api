package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an entity (account, payment, settlement, ...) cannot be
 * located by the requested id. Mapped to HTTP 404 by
 * {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String entityType, Object id) {
        super("NOT_FOUND", "%s with id %s was not found".formatted(entityType, id));
    }

    public ResourceNotFoundException(String message) {
        super("NOT_FOUND", message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.NOT_FOUND;
    }
}
