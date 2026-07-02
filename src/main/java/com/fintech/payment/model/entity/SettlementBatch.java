package com.fintech.payment.model.entity;

import com.fintech.payment.model.enums.SettlementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * SettlementBatch entity — SRS §3.3.
 *
 * <p>One row per {@code LocalDate}. The {@code @UniqueConstraint} on
 * {@code batch_date} prevents two OPEN batches for the same day, which keeps
 * the daily @Scheduled idempotent across retries (a second invocation of
 * {@code createDailyBatch()} on the same date hits a UNIQUE violation
 * rather than spawning a duplicate batch — caught and logged).</p>
 *
 * <p>Persistence parity with Payment:</p>
 * <ul>
 *   <li>{@code @EntityListeners(AuditingEntityListener.class)} populates
 *       {@code @CreatedDate} / {@code @LastModifiedDate} on persist / update
 *       (mirrors the Payment setup so the entire project uses a single
 *       auditing convention).</li>
 *   <li>{@code @Version Long} enables optimistic locking. The aggregate
 *       update at batch-finalize time bumps the version; concurrent
 *       workers racing on the same batch raise
 *       {@code OptimisticLockingFailureException} → the @Retryable layer
 *       re-runs the finalize.</li>
 * </ul>
 *
 * <p>Relationship to Payment (no JPA FK, defer to Phase 6 hardening):</p>
 * <ul>
 *   <li>{@code Payment.settlementBatchId} (added in Phase 4) carries the
 *       {@code SettlementBatch.id} as a plain nullable UUID. Reverse
 *       lookup via {@code PaymentRepository.findBySettlementBatchId}.</li>
 * </ul>
 */
@Entity
@Table(name = "settlement_batches",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_batches_batch_date",
                columnNames = "batch_date"),
        indexes = {
                @Index(name = "ix_settlement_batches_status", columnList = "status"),
                @Index(name = "ix_settlement_batches_batch_date_desc",
                        columnList = "batch_date DESC")
        })
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "batch_date", nullable = false, updatable = false)
    private LocalDate batchDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SettlementStatus status;

    @Column(name = "total_payments", nullable = false)
    private int totalPayments;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "processed_at")
    private Instant processedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
