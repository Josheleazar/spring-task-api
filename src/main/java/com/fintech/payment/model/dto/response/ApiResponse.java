package com.fintech.payment.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Generic success-envelope for every successful API response (SRS §6 — API Consistency).
 *
 * <p>The shape is intentionally minimal — only {@code data} and a server-provided
 * {@code timestamp}. HTTP status lives on the response wrapper, not in the body, so
 * {@link ApiResponse} stays free to wrap any payload shape. Pages, lists, and single
 * resources all serialise uniformly.</p>
 *
 * <p>Error envelopes live in {@link ApiErrorResponse} (created in Phase 1) — both
 * share the {@code timestamp} field but otherwise diverge by design, since error
 * responses carry machine-readable codes ({@code status}, {@code error}) that the
 * success envelope does not need.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, Instant timestamp) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, Instant.now());
    }
}
