package com.fintech.payment.service;

import com.fintech.payment.model.entity.Payment;
import com.fintech.payment.model.entity.SettlementBatch;
import com.fintech.payment.model.enums.PaymentStatus;
import com.fintech.payment.model.enums.SettlementStatus;
import com.fintech.payment.repository.PaymentRepository;
import com.fintech.payment.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 4 settlement transaction worker — extracted from {@link SettlementWorker}
 * into a separate {@code @Service} so cross-bean invocation correctly
 * activates the {@code @Transactional} AOP proxy.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * <p>The Phase-2 deep-review §12.2.1 lesson applies here verbatim: a method
 * annotated {@code @Transactional} that is invoked via {@code this.method(...)}
 * from the same class bypasses the Spring AOP proxy and silently runs
 * without a transaction. The original
 * {@link SettlementWorker#processBatchAsync} called
 * {@code this.processBatchTransactional(...)}; even though the helper
 * carried {@code @Transactional}, the proxy never intercepted the call,
 * so the multi-row claim loop ran with per-row auto-commit semantics —
 * breaking FR-3.3's atomic-batch-settlement guarantee.</p>
 *
 * <p>Fix: lift the two transactional helpers ({@link #processBatchTransactional},
 * {@link #markBatchFailed}) into a separate {@code @Service}. The
 * {@code SettlementWorker} now injects this bean and delegates. The
 * cross-bean call goes through Spring's proxy machinery and the
 * {@code @Transactional} annotations activate as intended.</p>
 *
 * <h2>Propagation</h2>
 *
 * <p>{@link Propagation#REQUIRES_NEW} guarantees a fresh transaction on
 * each invocation — critical when {@code SettlementWorker.processBatchAsync}
 * is invoked from the {@code @Async} thread pool, where there's no
 * ambient tx context to inherit. Without {@code REQUIRES_NEW}, the worker's
 * tx would have to be inherited from somewhere; with it, every batch
 * claims-and-finalises in a self-contained tx that rolls back cleanly on
 * any unrecoverable failure.</p>
 *
 * <h2>FR coverage</h2>
 * <ul>
 *   <li>FR-3.2 ("Process pending payments into an OPEN batch") — the
 *       claim-and-final-seal sequence in {@link #processBatchTransactional}.</li>
 *   <li>FR-3.3 ("Execute batch settlement asynchronously") — atomic
 *       tx boundary replaces the per-row auto-commit that was the
 *       self-invocation defect. The atomicity guarantee is now real, not
 *       merely nominal.</li>
 *   <li>FR-3.4 ("Retry failed settlements") — interacts via
 *       {@link SettlementWorker} which holds the {@code @Retryable}
 *       on the outer call. Tx isolation is preserved even across retries
 *       because {@code REQUIRES_NEW} spawns a fresh tx per attempt.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SettlementTransactionalService {

    private final SettlementRepository settlementRepository;
    private final PaymentRepository paymentRepository;
    private final Clock clock;

    /**
     * Single transactional boundary: flip the batch to {@code PROCESSING},
     * claim every {@code COMPLETED} + {@code settlementBatchId IS NULL} payment,
     * stamp the batch as {@code SETTLED} with the aggregate totals.
     *
     * <p>Per-payment {@code save()} on a row whose {@code @Version} was
     * concurrently bumped raises {@code OptimisticLockingFailureException}
     * which propagates up to {@link SettlementWorker#processBatchAsync}'s
     * {@code @Retryable} boundary and triggers a retry of the whole batch.</p>
     */
    public void processBatchTransactional(UUID batchId) {
        SettlementBatch batch = settlementRepository.findById(batchId)
                .orElseThrow(() -> new IllegalStateException(
                        "SettlementBatch " + batchId + " missing mid-processing"));
        if (batch.getStatus() == SettlementStatus.SETTLED) {
            log.info("batchId={} already SETTLED — skipping", batchId);
            return;
        }

        // 1. Claim — flip to PROCESSING first so concurrent /process triggers
        //    observe the in-progress state via GET /api/v1/settlements/{id}.
        batch.setStatus(SettlementStatus.PROCESSING);
        settlementRepository.save(batch);

        // 2. Find all COMPLETED payments with no settlementBatchId assigned.
        //    Today's claim window is unconstrained (Phase 4 KISS) — Phase 6
        //    will add a date-range filter on Payment.createdAt when batches
        //    are picked up at midnight rather than off-cycle.
        List<Payment> claimable = paymentRepository
                .findByStatusAndSettlementBatchIdIsNull(PaymentStatus.COMPLETED);

        log.info("batchId={} claims {} payments", batchId, claimable.size());
        int totalCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        Instant stamp = Instant.now(clock);

        for (Payment p : claimable) {
            p.setSettlementBatchId(batchId);
            p.setProcessedAt(stamp);
            // OptimisticLockingFailureException on concurrent user-API @Version bump
            // propagates to SettlementWorker.processBatchAsync's @Retryable.
            paymentRepository.save(p);
            totalCount++;
            totalAmount = totalAmount.add(p.getAmount());
        }

        // 3. Finalize — single aggregate update on the batch row.
        //    Incrementally updating during the claim loop would cause
        //    OptimisticLockingFailureException storms on the batch row
        //    if multiple @Async invocations raced.
        batch.setStatus(SettlementStatus.SETTLED);
        batch.setTotalPayments(totalCount);
        batch.setTotalAmount(totalAmount);
        batch.setProcessedAt(stamp);
        settlementRepository.save(batch);

        log.info("batchId={} SETTLED: totalPayments={} totalAmount={}",
                batchId, totalCount, totalAmount);
    }

    /**
     * Fallback path when Spring Retry exhausts {@code maxAttempts} on the
     * outer call. Runs in its own (new) transaction so the failure state
     * records even after the worker tx has rolled back.
     *
     * <p>Idempotent against already-FAILED batches (a findById no-op on
     * a missing batch logs at INFO rather than throwing — this is a
     * post-retry housekeeping call, not a user-facing API).</p>
     */
    public void markBatchFailed(UUID batchId, String reason) {
        settlementRepository.findById(batchId).ifPresent(b -> {
            b.setStatus(SettlementStatus.FAILED);
            b.setProcessedAt(Instant.now(clock));
            settlementRepository.save(b);
            log.info("batchId={} marked FAILED (reason='{}')", batchId, reason);
        });
    }
}
