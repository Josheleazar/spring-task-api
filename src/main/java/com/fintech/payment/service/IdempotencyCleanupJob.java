package com.fintech.payment.service;

import com.fintech.payment.repository.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Phase 4 idempotency TTL cleanup (SRS §6 NFR: "Idempotency keys expire
 * after 24h" + §12.3 deferred-to-Phase-4 item).
 *
 * <p>Hourly cron {@code "0 0 * * * *"} (top of every hour) deletes
 * {@link com.fintech.payment.model.entity.IdempotencyRecord} rows whose
 * {@code createdAt} is older than {@code Instant.now() - 24h}. Bulk
 * JPQL DELETE for memory-bounded batch deletion — Spring Data generates
 * "DELETE WHERE createdAt < ?" without loading rows.</p>
 *
 * <p>Idempotency-key replay after eviction still lands in
 * {@code PaymentService.submitPayment} → DB unique constraint →
 * {@code 409 IDEMPOTENCY_KEY_CONFLICT} (the §12.3 deep-review documented
 * the DB-vs-cache dual-layer architecture). Cache eviction is therefore
 * safe; replay preserves correctness via the secondary backstop.</p>
 *
 * <p>Test contract:</p>
 * <ul>
 *   <li>{@code @DataJpaTest} slice: directly invoke
 *       {@code repository.deleteByCreatedAtBefore(cutoff)} in a test, no
 *       need to fire the cron.</li>
 *   <li>Full {@code @SpringBootTest}: disable scheduling via
 *       {@code spring.task.scheduling.pool.size=0} or a dedicated test
 *       profile property to avoid unrelated background ticks during test
 *       run.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyCleanupJob {

    /** TTL window matches SRS §6 ("expire after 24h"). */
    private static final long TTL_HOURS = 24;

    private final IdempotencyRepository repository;
    private final Clock clock;

    /**
     * Hourly cron. {@code cron = "0 0 * * * *"} fires at the top of every
     * hour (:00:00). Bulk DELETE — no row loading.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now(clock).minus(TTL_HOURS, ChronoUnit.HOURS);
        int deleted = repository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Idempotency TTL cleanup: deleted {} rows older than {}", deleted, cutoff);
        } else {
            log.debug("Idempotency TTL cleanup: nothing to delete (cutoff={})", cutoff);
        }
    }
}
