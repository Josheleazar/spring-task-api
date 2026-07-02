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
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void createDailyBatch() {
        LocalDate today = LocalDate.now(clock);
        try {
            SettlementBatch batch = new SettlementBatch();
            batch.setBatchDate(today);
            batch.setStatus(SettlementStatus.OPEN);
            batch.setTotalPayments(0);
            batch.setTotalAmount(java.math.BigDecimal.ZERO);
            batch.setCurrency("USD");  // FR-3.1 KISS — one batch per day, USD-aggregate only
            SettlementBatch saved = settlementRepository.save(batch);
            log.info("Created settlement batch id={} for {}", saved.getId(), today);
            // Phase 5: emit the BATCH_OPEN audit row programmatically. The
            // @Audited annotation is argument-driven (entityIdArg = "batchId")
            // and would not fit a no-arg @Scheduled tick; the alternative
            // shape (post-proceed return-value resolution on the @Scheduled
            // method) is documented in §12.5 as a Phase-6 hardening path.
            // AuditService.record() runs in REQUIRES_NEW so the audit row
            // commits independently of the daily-tick's persistence.
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
