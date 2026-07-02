package com.fintech.payment.model.entity;

import com.fintech.payment.model.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment entity — SRS §3.2.
 *
 * <p>Persistence notes:</p>
 * <ul>
 *   <li>{@code idempotencyKey} carries a UNIQUE-constraint so two concurrent
 *       submissions of the same client-supplied key cannot both persist a row
 *       (TOCTOU race caught at flush → {@code DataIntegrityViolationException}
 *       SQL state 23505 → {@code 409 IDEMPOTENCY_KEY_CONFLICT}). The filter
 *       layer doesn't need to "claim" the key in advance.</li>
 *   <li>{@code sourceAccountId} / {@code targetAccountId} are stored as plain
 *       UUID columns without a JPA {@code @ManyToOne} relationship. Load via
 *       the {@code AccountRepository}. Phase 6 may add FK constraints when
 *       H2 PG-compat visibility is moved onto real PostgreSQL migrations; the
 *       Phase-2 deep-review 23503 → 404 backstop handles any FK miss.</li>
 *   <li>{@code @Version} enables optimistic locking. Two payments racing on the
 *       same source account lose-update correctly — the second save throws
 *       {@code OptimisticLockingFailureException} → {@code 409 CONCURRENCY_CONFLICT}.</li>
 *   <li>{@code EntityListeners(AuditingEntityListener.class)} populates
 *       {@code @CreatedDate} / {@code @LastModifiedDate} on persist / update.</li>
 *   <li>{@code failureReason} holds gateway error text on FAILED/REVERSED
 *       payments; column widened to {@code length = 2048} per §12.3.3 to fit
 *       real-world Stripe/Sift/Forter response strings. Phase 7 may upgrade
 *       to a {@code @Lob TEXT} column if verbose gateway dumps surface
 *       (decision recorded in §12.3.3).</li>
 * </ul>
 */
@Entity
@Table(name = "payments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payments_idempotency_key",
                columnNames = "idempotency_key"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 2048)
    private String failureReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}
