package com.fintech.payment.repository;

import com.fintech.payment.model.entity.Payment;
import com.fintech.payment.model.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Payment}. Adds:
 * <ul>
 *   <li>{@code findByIdempotencyKey} — used by the {@code IdempotencyFilter}
 *       to short-circuit on cache hits (we keep the index on the
 *       {@code IdempotencyRecord} table, but a {@code Payment}-side lookup
 *       supports the deeper "did we already process this?" question).</li>
 *   <li>{@code findByStatus} + {@link Pageable} — backs
 *       {@code GET /api/v1/payments?status=COMPLETED}.</li>
 * </ul>
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);
}
