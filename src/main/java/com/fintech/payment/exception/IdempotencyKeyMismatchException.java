package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 6 §12.6.1 item (5) — body-hash mismatch.
 *
 * <p>Thrown when the SHA-256 of the incoming request body does not match
 * the {@code body_hash} stored alongside the cached idempotency record.
 * This typically means the client reused an idempotency key for a
 * different payload (an HTTP-level misuse), so we replay-fail rather
 * than silently producing the cache's previous response.</p>
 *
 * <p>The error code {@code IDEMPOTENCY_KEY_BODY_MISMATCH} maps to
 * 422 UNPROCESSABLE_ENTITY via {@link #getHttpStatus()} override —
 * the schema itself is well-formed (request parses cleanly), but the
 * semantic content is incompatible with the cached row. 422 is the
 * canonical RFC-4918 status for this case (per FR-2.2 hardening).</p>
 */
public class IdempotencyKeyMismatchException extends DomainException {

    public IdempotencyKeyMismatchException(String idempotencyKey, String expectedHash, String actualHash) {
        super("IDEMPOTENCY_KEY_BODY_MISMATCH",
                "Idempotency-Key '" + idempotencyKey
                        + "' was cached for a different request body "
                        + "(expected sha256=" + expectedHash + ", got " + actualHash + ")");
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
