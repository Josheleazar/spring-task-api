package com.fintech.payment.repository;

import com.fintech.payment.model.entity.Payment;
import com.fintech.payment.model.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Payment}.
 *
 * <p>Phase-3 surface:</p>
 * <ul>
 *   <li>{@code findByIdempotencyKey} — used by the {@code IdempotencyFilter}
 *       to short-circuit on cache hits, plus future replay-detection at the
 *       service layer.</li>
 *   <li>{@code findByStatus} + {@link Pageable} — backs
 *       {@code GET /api/v1/payments?status=COMPLETED}.</li>
 * </ul>
 *
 * <p>Phase-4 additions:</p>
 * <ul>
 *   <li>{@code findByStatusAndSettlementBatchIdIsNull} — backs the
 *       SettlementWorker's claim loop ("find COMPLETED payments that have
 *       not yet been swept into a batch").</li>
 *   <li>{@code findBySettlementBatchId} — reverse-lookup for batch-detail
 *       views + audit trails.</li>
 * </ul>
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    // Phase 4 — settlement-claim support

    List<Payment> findByStatusAndSettlementBatchIdIsNull(PaymentStatus status);

    List<Payment> findBySettlementBatchId(UUID settlementBatchId);

    long countBySettlementBatchId(UUID settlementBatchId);
}
