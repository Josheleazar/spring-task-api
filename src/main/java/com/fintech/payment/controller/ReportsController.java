package com.fintech.payment.controller;

import com.fintech.payment.model.dto.response.ReconciliationReportResponse;
import com.fintech.payment.service.ReconciliationReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Reconciliation-report endpoint — Phase 5 / FR-4.3.
 *
 * <p>Endpoint shape (per SRS §5.5):</p>
 * <pre>
 *   GET /api/v1/reports/daily?date=2026-07-01
 * </pre>
 *
 * <p>Response contracts:</p>
 * <ul>
 *   <li>ISO-8601 {@code yyyy-MM-dd} date format for {@code date}.</li>
 *   <li>Future date (after today UTC) → 422
 *       {@code RECONCILIATION_REPORT_UNAVAILABLE}.</li>
 *   <li>Malformed date → 400 {@code TYPE_MISMATCH} via the global
 *       exception handler.</li>
 * </ul>
 *
 * <p>This endpoint is independent of the {@code AuditController} —
 * the audit query is row-level (FR-4.2); this is the daily aggregate
 * (FR-4.3). A real production system would split the read APIs further
 * (e.g. {@code /reports/daily/2026-07-01} as a cached-or-precomputed
 * path) but Phase 5 keeps the surface flat.</p>
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportsController {

    private final ReconciliationReportService reconciliationReportService;

    /**
     * Compute-on-demand daily reconciliation tally for a given past date.
     *
     * @param date ISO {@code yyyy-MM-dd}; today-or-past (UTC).
     * @return the assembled report envelope.
     */
    @GetMapping("/daily")
    public ReconciliationReportResponse getDailyReport(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ReconciliationReportResponse report = reconciliationReportService.generateReport(date);
        log.debug("Reconciliation report date={} totalAuditEvents={}",
                date, report.totalAuditEvents());
        return report;
    }
}
