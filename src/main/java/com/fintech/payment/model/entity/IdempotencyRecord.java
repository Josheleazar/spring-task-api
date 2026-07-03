package com.fintech.payment.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * IdempotencyRecord entity — SRS §3.4 (Phase 3 + Phase 6 body-hash).
 *
 * <p>The {@code idempotencyKey} is the natural primary key, not a UUID —
 * clients supply it as a stable opaque string, so we use it directly as the
 * table PK to avoid an extra surrogate id column.</p>
 *
 * <h2>Phase 6 body-hash column (item 5 / §12.6.1)</h2>
 *
 * <p>{@link #bodyHash} stores a SHA-256 hex digest of the request body
 * bytes that produced the cached {@link #responseBody}. On a cache hit,
 * the filter hashes the incoming request body and compares against this
 * column; mismatch raises {@code IdempotencyKeyMismatchException} (422
 * IDEMPOTENCY_KEY_BODY_MISMATCH per FR-2.2 hardening), preventing
 * silently-replaying a cached response against an unrelated request that
 * happens to share the same idempotency key (the §12.3 deferred item
 * raised in §12.3.2 hermeticity notes).</p>
 *
 * <p>{@code bodyHash} is fixed 64 chars (SHA-256 hex), nullable on rows
 * persisted before Phase 6 — backward-compatible with prior cache rows.</p>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>Cache-miss POST: filter writes one of these rows in a
 *       {@code REQUIRES_NEW} transaction after the controller commits, so
 *       4xx failures (validation, insufficient funds, self-transfer, …) are
 *       cached as faithfully as 2xx successes (per §12.3.3 — the
 *       post-Phase-3 deep-review fix widened the cache guard from
 *       2xx-only to all controller-committed responses; the §12.6.1 item
 *       (5) reasserts the 2xx-only policy with body-hash reinforcement).</li>
 *   <li>Cache-hit POST: filter reads this row verbatim and writes the cached
 *       status + body back to {@code HttpServletResponse} — never enters
 *       the controller.</li>
 *   <li>TTL: SRS §6 specifies idempotency keys expire after 24h. Phase 3
 *       does not run a cleanup job; callers should treat cached records as
 *       advisory. A scheduled cleanup is a Phase 4 / Phase 5 enhancement.</li>
 * </ul>
 */
@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    /**
     * SHA-256 hex digest of the original request body. Nullable on rows
     * persisted pre-Phase-6 so legacy cache rows continue to deserialize.
     * Phase-6 writes always populate this column.
     */
    @Column(name = "body_hash", length = 64, updatable = false)
    private String bodyHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
