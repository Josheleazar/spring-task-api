package com.fintech.payment.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Standard error response envelope (SRS §8).
 *
 * <p>{@code details} is used for domain-specific extra context (e.g. insufficient
 * funds payload) and {@code fieldErrors} is used for bean-validation failures.
 * Both are nullable and omitted from the JSON when {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp,
        String path,
        Map<String, Object> details,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {

        public static FieldError of(String field, String message) {
            return new FieldError(field, message);
        }
    }
}
