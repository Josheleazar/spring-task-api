package com.fintech.payment.repository;

import com.fintech.payment.model.entity.SettlementBatch;
import com.fintech.payment.model.enums.SettlementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SettlementBatch}. Adds:
 * <ul>
 *   <li>{@code findByBatchDate} — supports the daily @Scheduled guard
 *       ("is there an OPEN batch for today yet?").</li>
 *   <li>{@code findByStatus} + {@link Pageable} — backs
 *       {@code GET /api/v1/settlements?status=OPEN}.</li>
 * </ul>
 *
 * <p>The {@code @UniqueConstraint} on {@code batch_date} means
 * {@code findByBatchDate} is index-backed; the {@code @Index} on
 * {@code status} (declared in {@link SettlementBatch#indexes()}) backs
 * the status filter.</p>
 */
@Repository
public interface SettlementRepository extends JpaRepository<SettlementBatch, UUID> {

    Optional<SettlementBatch> findByBatchDate(LocalDate batchDate);

    Page<SettlementBatch> findByStatus(SettlementStatus status, Pageable pageable);
}
