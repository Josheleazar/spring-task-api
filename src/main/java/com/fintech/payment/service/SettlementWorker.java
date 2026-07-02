package com.fintech.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase 4 settlement worker — covers FR-3.3 (@Async execution) + FR-3.4
 * (Spring Retry on {@link OptimisticLockingFailureException}, the §12.3.3
 * deferred item).
 *
 * <h2>Architecture (§12.4.self-invocation-defect)

Originally this class held both the {@code @Async} + {@code @Retryable}
 * {@link #processBatchAsync} method AND its {@code @Transactional} helper.
 * The Phase-4 reviewer flagged this as the same defect class as Phase-2
 * §12.2.1's self-invocation b
ypass — a {@code @Transactional} method called
 * via {@code this.…} from the same class runs without a tx because the
 * AOP proxy is bypassed. The fix: extract the transactional helpers into
 * {@link SettlementTransactionalService}, which this class now injects.
 * Cross-bean invocation correctly activates the proxy.</h2>
 *
 * <p>The retryable exception is just {@link OptimisticLockingFailureException}
 * today (Phase-4 KISS; {@code PaymentGatewayClient} is deferred to Phase 7
 * per the deferred-II item list). {@code maxAttempts=3}, {@code @Backoff(delay=500)}
 * — enough j
itter to skirt most CAS contention without delaying completion
 * noticeably.</p>
 *
 * <p>{@link #processBatchAsync} returns {@code void}. Outcomes are
 * observable via the GET /{id} polling path.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementWorker {

    private final SettlementTransactionalService transactionalService;

    /**
     * Async + Retryable entry point. Delegates to the transactional helper
     * bean — cross-bean invocation activates the {@code @Transactional} proxy.
     */
    @Async("settlementWorkerExecutor")
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500))
    public void processBatchAsync(UUID batchId) {
        log.info("SettlementWorker starting batchId={}", batchId);
        try {
            transactionalService.processBatchTransactional(batchId);
        } catch (RuntimeException ex) {
            log.warn("SettlementWorker exhausted retries on batchId={}: {}", batchId, ex.toString());
            transactionalService.markBatchFailed(batchId, ex.getMessage());
        }
    }
}
