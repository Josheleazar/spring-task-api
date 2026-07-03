package com.fintech.payment.service;

import com.fintech.payment.exception.SettlementBatchNotFoundException;
import com.fintech.payment.model.dto.response.SettlementResponse;
import com.fintech.payment.model.entity.SettlementBatch;
import com.fintech.payment.model.enums.AuditAction;
import com.fintech.payment.model.enums.SettlementStatus;
import com.fintech.payment.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Phase 4 settlement service — covers FR-3.1, FR-3.5 + the
 * {@code POST /process} trigger.
 *
 * <p>Architecture:</p>
 * <ul>
 *   <li>Daily @Scheduled at midnight creates one OPEN {@code SettlementBatch}
 *       row for the current {@code LocalDate}. The @UniqueConstraint on
 *       {@code batch_date} keeps the call idempotent against re-runs.</li>
 *   <li>Async batch processing lives in {@link SettlementWorker}, not here.
 *       The service is the {@code @Scheduled} / REST entry point; the
 *       worker is the {@code @Async} + {@code @Retryable} hot loop. The
 *       split mirrors the Phase-2 self-invocation gotcha lesson (don't put
 *       mutating helpers behind a single proxy chain).</li>
 *   <li>Listing helpers {@link #listBatches} and {@link #getBatch} are
 *       read-only and run within the class-level {@code @Transactional(readOnly = true)}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final SettlementWorker settlementWorker;
    private final com.fintech.payment.service.AuditService auditService;
    private final Clock clock;

    /* -------------------- FR-3.1: Daily batch creation -------------------- */

    /**
     * Creates one OPEN {@code SettlementBatch} for today's {@code LocalDate}
     * if none exists. Idempotent — a second invocation hits the unique
     * constraint on {@code batch_date} and is silently tolerated (logged at
     * INFO; the day already has a batch).
     *
     * <p>Phase 6 §12.6.1 item (1): the worker dispatch is wrapped in a
     * try/catch on {@link RejectedExecutionException} so a queue overflow
     * on the {@code settlementWorkerExecutor} (core=2 / max=4 / queue=50
     * per {@code AsyncConfig}) does not leave a freshly-persisted OPEN
     * batch row stranded without an eventual worker attempt. The handler
     * emits a {@link AuditAction#BATCH_REJECTED} audit row so operations
     * can detect stranded batches via the {@code /api/v1/audit?entityType=SETTLEMENT_BATCH}
     * endpoint and trigger a manual {@code POST /process} to recover.</p>
     *
     * <p>The method is explicitly {@code @Transactional} (read-write) to
     * escape the class-level {@code readOnly=true} inheritance — the
     * persisted batch row + audit inserts are writes and would otherwise
     * be quietly hinted-as-readonly against stricter Hibernate flush
     * policies. The Phase-6 reviewer flagged this as a forward-flag.</p>
     *
     * <p>The {@code DataIntegrityViolationException} catch (unique-constraint
     * race) is preserved at the same level — both rejection paths are
     * distinguishable in the audit log via distinct {@code action} values.</p>
     */
    @Transactional
    @Scheduled(cron = "0 0 0 * * *")
    public void createDailyBatch() {
        LocalDate today = LocalDate.now(clock);
        SettlementBatch saved = null;
        boolean createSucceeded = false;
        try {
            SettlementBatch batch = new SettlementBatch();
            batch.setBatchDate(today);
            batch.setStatus(SettlementStatus.OPEN);
            batch.setTotalPayments(0);
            batch.setTotalAmount(java.math.BigDecimal.ZERO);
            batch.setCurrency("USD");  // FR-3.1 KISS — one batch per day, USD-aggregate only
            saved = settlementRepository.save(batch);
            createSucceeded = true;
            log.info("Created settlement batch id={} for {}", saved.getId(), today);
            // Phase 5: emit the BATCH_OPEN audit row programmatically. The
            // @Audited annotation is argument-driven (entityIdArg = "batchId")
            // and would not fit a no-arg @Scheduled tick. AuditService.record()
            // runs in REQUIRES_NEW so the audit row commits independently of
            // the daily-tick's persistence.
            auditService.record(
                    AuditAction.BATCH_OPEN,
                    "SETTLEMENT_BATCH",
                    saved.getId(),
                    null,
                    null,
                    "system");
            // Trigger the worker — @Async + @Retryable wraps the actual processing.
            settlementWorker.processBatchAsync(saved.getId());
        } catch (DataIntegrityViolationException ex) {
            log.info("Settlement batch for {} already exists (race or duplicate tick) — skipped", today);
        } catch (RejectedExecutionException ex) {
            // §12.6.1 item (1): worker pool rejection. The batch row IS persisted
            // at this point (createSucceeded==true) but the worker was rejected.
            // Emit BATCH_REJECTED so operators see the gap; do NOT roll back
            // the batch row — the row's existence is the truth, the @Async
            // submission is best-effort. An operator can recover via
            // POST /api/v1/settlements/{id}/process (manual trigger).
            if (createSucceeded && saved != null) {
                log.warn("Worker rejected dispatch for batchId={} date={}: {} — batch row remains OPEN; " +
                        "audit row BATCH_REJECTED emitted for ops visibility",
                        saved.getId(), today, ex.toString());
                auditService.record(
                        AuditAction.BATCH_REJECTED,
                        "SETTLEMENT_BATCH",
                        saved.getId(),
                        null,
                        ex.toString(),
                        "system");
            } else {
                log.warn("Worker rejection before batch row persisted (date={}): {}",
                        today, ex.toString());
            }
        }
    }

    /* -------------------- FR-3.5: View + listing -------------------- */

    public SettlementResponse getBatch(UUID id) {
        return settlementRepository.findById(id)
                .map(SettlementResponse::from)
                .orElseThrow(() -> new SettlementBatchNotFoundException(id));
    }

    public Page<SettlementResponse> listBatches(SettlementStatus status, Pageable pageable) {
        Page<SettlementBatch> page = (status == null)
                ? settlementRepository.findAll(pageable)
                : settlementRepository.findByStatus(status, pageable);
        return page.map(SettlementResponse::from);
    }

    /**
     * FR-3.2/FR-3.3 manual trigger: POST /api/v1/settlements/{id}/process.
     * Idempotent against batches already SETTLED — the worker re-runs the
     * finalize logic and the @Version on the batch detects the no-op (a
     * batch already SETTLED will not gain new claims because findClaimable
     * returns empty).
     */
    public void triggerProcessing(UUID id) {
        SettlementBatch batch = settlementRepository.findById(id)
                .orElseThrow(() -> new SettlementBatchNotFoundException(id));
        if (batch.getStatus() == SettlementStatus.SETTLED || batch.getStatus() == SettlementStatus.FAILED) {
            log.info("Manual trigger on terminal-state batch id={} (status={}) — worker will converge", id, batch.getStatus());
        }
        settlementWorker.processBatchAsync(id);
    }
}
