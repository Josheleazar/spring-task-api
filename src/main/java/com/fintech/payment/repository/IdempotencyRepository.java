package com.fintech.payment.repository;

import com.fintech.payment.model.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Repository for {@link IdempotencyRecord}. The {@code String} type parameter
 * reflects that {@code idempotencyKey} is itself the primary key — no surrogate
 * id needed for retrieval.
 *
 * <p>Phase-4 addition: {@link #deleteByCreatedAtBefore(Instant)} backs the
 * hourly {@code IdempotencyCleanupJob.purgeExpired} cron (SRS §6 NFR:
 * "Idempotency keys expire after 24h"). Spring Data generates a bulk
 * JPQL DELETE — no row loading, no SELECT-then-DELETE-N round-trips.</p>
 */
@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Bulk-delete idempotency records older than {@code cutoff}. Returns
     * the number of rows deleted (Spring Data convention).
     */
    int deleteByCreatedAtBefore(Instant cutoff);
}
