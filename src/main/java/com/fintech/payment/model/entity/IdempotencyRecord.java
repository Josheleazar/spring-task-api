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
 * IdempotencyRecord entity — SRS §3.4 (Phase 3).
 *
 * <p>The {@code idempotencyKey} is the natural primary key, not a UUID —
 * clients supply it as a stable opaque string, so we use it directly as the
 * table PK to avoid an extra surrogate id column.</p>
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>Cache-miss POST: filter writes one of these rows in a
 *       {@code REQUIRES_NEW} transaction after the controller commits, so
 *       4xx failures (validation, insufficient funds, self-transfer, …) are
 *       cached as faithfully as 2xx successes.</li>
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
