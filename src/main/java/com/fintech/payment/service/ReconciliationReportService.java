package com.fintech.payment.service;

import com.fintech.payment.exception.ReconciliationReportUnavailableException;
import com.fintech.payment.model.dto.response.ReconciliationReportResponse;
import com.fintech.payment.model.entity.AuditLog;
import com.fintech.payment.model.enums.AuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 5 reconciliation report — FR-4.3.
 *
 * <p>Two entry points:</p>
 * <ul>
 *   <li>{@link #generateReport(LocalDate)} — synchronous, compute-on-demand.
 *       Backed by {@code GET /api/v1/reports/daily?date=...}. Throws
 *       {@link ReconciliationReportUnavailableException} for future dates.</li>
 *   <li>{@link #prewarmYesterday()} — {@code @Scheduled} tick at 00:30 UTC
 *       (30 minutes after {@link SettlementService#createDailyBatch} fires
 *       at 00:00). Computes yesterday's report and logs the summary at
 *       INFO so operators have a stable, audit-trail-trackable artefact
 *       without an HTTP round-trip.</li>
 * </ul>
 *
 * <p>Cache note: Phase 5 ships no Redis / in-process cache layer —
 * {@code fromCache} is always {@code false}. The {@code fromCache} field
 * is reserved for a Phase-6 pluggable cache (the SRS §1.3 Production
 * Roadmap item on Redis / Caffeine is parked for production hardening).</p>
 *
 * <p>Phase-5 KISS scope (SRS §3.5 + §7): a daily tally in two
 * groupbys ({@code byEntityType}, {@code byAction}). No reconciliation
 * between audit-log events and ledger balances yet — that lives in
 * Phase 6 hardening (it's the "discrepancy detection" sibling to the
 * Phase-5 surface, gated behind an extension point).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationReportService {

    private final AuditService auditService;
    private final Clock clock;

    /**
     * Computes the reconciliation report for an arbitrary past date.
     * Used by the FR-4.3 GET endpoint.
     *
     * @param date the calendar day this report covers (UTC).
     * @return the assembled report envelope.
     * @throws ReconciliationReportUnavailableException if {@code date}
     *         is after today (UTC) — there are no audit events to tally.
     */
    public ReconciliationReportResponse generateReport(LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) {
            throw new ReconciliationReportUnavailableException(
                    "Reconciliation report for future date " + date + " is unavailable. " +
                            "Today (UTC) is " + today + ".");
        }

        Instant windowStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<AuditLog> rows = auditService.findByWindow(windowStart, windowEnd);

        Map<String, Long> byEntityType = new TreeMap<>();
        Map<String, Long> byAction = new TreeMap<>();
        for (AuditLog row : rows) {
            byEntityType.merge(row.getEntityType(), 1L, Long::sum);
            byAction.merge(row.getAction().name(), 1L, Long::sum);
        }

        ReconciliationReportResponse response = new ReconciliationReportResponse(
                date,
                rows.size(),
                byEntityType,
                byAction,
                false,            // fromCache — Phase 5 ships always false
                windowStart,
                windowEnd,
                Instant.now(clock)
        );
        return response;
    }

    /**
     * {@code @Scheduled} pre-warm tick at 00:30 UTC. 30 minutes is just
     * enough window for {@code SettlementService.createDailyBatch}'s
     * midnight @{@code @Async} claim-and-finalize to emit the day's
     * BATCH_OPEN and BATCH_SETTLED audit rows; we then summarise them
     * into the daily tally and log at INFO so operators see one log
     * line per day with the day's totals.
     *
     * <p>The cron expression follows Spring's six-field syntax
     * (sec min hour day month dow).</p>
     */
    @Scheduled(cron = "0 30 0 * * *")
    public void prewarmYesterday() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        try {
            ReconciliationReportResponse report = generateReport(yesterday);
            log.info("Reconciliation pre-warm for {}: totalAuditEvents={} byEntityType={} byAction={}",
                    yesterday, report.totalAuditEvents(),
                    report.byEntityType(), report.byAction());
        } catch (Exception ex) {
            // Pre-warm must never crash the @Scheduled executor —
            // log + swallow. Tomorrow's tick will retry.
            log.warn("Reconciliation pre-warm for {} failed: {}", yesterday, ex.toString());
        }
    }
}
