package com.fintech.payment.model.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * FR-4.3 reconciliation-report response. Phase-5 KISS shape:
 *
 * <ul>
 *   <li>{@code date} — the day the report covers (UTC).</li>
 *   <li>{@code totalAuditEvents} — count of {@code AuditLog} rows whose
 *       {@code createdAt} falls in {@code [date 00:00, date+1 00:00)}.</li>
 *   <li>{@code byEntityType} — count per entity type ({@code "PAYMENT" /
 *       "ACCOUNT" / "SETTLEMENT_BATCH"}). Clients can derive their own
 *       percentages.</li>
 *   <li>{@code byAction} — count per {@link com.fintech.payment.model.enums.AuditAction}
 *       ({@code CREATED / STATUS_CHANGE / REVERSED / …}).</li>
 *   <li>{@code fromCache} — true if the report was served from a
 *       precomputed/cache path (Phase 5 ships always false; placeholder
 *       for a Phase-6 cache layer).</li>
 *   <li>{@code generatedAt} — when this response was assembled (UTC).</li>
 *   <li>{@code windowStart}/{@code windowEnd} — the exact audit-event
 *       window this report covers, useful for verification.</li>
 * </ul>
 *
 * <p>The shape is intentional: Phase 5 ships a
 * <em>compute-on-demand</em> endpoint at GET time. The
 * {@code ReconciliationReportService} also runs a {@code @Scheduled(0 30 0 * * *)}
 * pre-warm for yesterday's report, which logs the same JSON to the
 * application logs (operationally visible without an HTTP round-trip).</p>
 */
public record ReconciliationReportResponse(
        LocalDate date,
        long totalAuditEvents,
        Map<String, Long> byEntityType,
        Map<String, Long> byAction,
        boolean fromCache,
        Instant windowStart,
        Instant windowEnd,
        Instant generatedAt
) {}
