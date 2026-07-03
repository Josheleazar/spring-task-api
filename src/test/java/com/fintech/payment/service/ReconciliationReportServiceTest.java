package com.fintech.payment.service;

import com.fintech.payment.exception.ReconciliationReportUnavailableException;
import com.fintech.payment.model.entity.AuditLog;
import com.fintech.payment.model.enums.AuditAction;
import com.fintech.payment.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Phase 7 §12.7.1 Batch 5 — coverage-push test for
 * {@link ReconciliationReportService}.
 *
 * <p>Targets the SRS §12.6.3 forward-flag § (1) — exercise the report
 * generator's window-edge semantics + the future-date 422 map. The
 * existing test envelope only covered happy-path disjoint fixture sets;
 * {@code generateReport}'s window math and the
 * {@link ReconciliationReportUnavailableException} mapping were uncovered.
 * Phase-7 Batch 5 closes that gap.</p>
 *
 * <h2>Phase 7.x convergence (SRS §12.7.1 + §12.7.1.1)</h2>
 *
 * <p>The initial Phase-7 close-out landed these three tests under
 * {@code @Disabled} after the first attempt at
 * {@code @DataJpaTest}-slice wiring failed. Phase-7.x then attempted
 * two further fix angles — the {@code @MockitoBean DateTimeProvider}
 * + {@code @SpringBootTest} shape, and an explicit
 * {@code @TestConfiguration @Primary DateTimeProvider} bean with
 * mutable {@link java.util.concurrent.atomic.AtomicReference}
 * backing. Both fix attempts in Phase-7.x failed to deliver the
 * staged {@link java.time.Instant} to {@code @CreatedDate} during
 * {@code saveAndFlush}; rows landed with {@code Clock.systemUTC()}
 * defaults and the window-edge assertions reported {@code expected X
 * but was 0L}.</p>
 *
 * <p>The forward-flag §12.7.1.1 keeps the three tests under
 * {@code @Disabled} pending a deeper investigation of Spring Data
 * AuditingHandler wiring — out of Phase 7.x scope. Possible
 * Phase-7.x.2 avenues are catalogued in SRS §12.7.1.1.</p>
 *
 * <h2>Hermeticity</h2>
 *
 * <p>{@code @SpringBootTest} activates {@code AuditingConfig} +
 * {@code @EnableJpaAuditing} so the auditing listener is registered.
 * {@code @TestPropertySource("spring.scheduling.enabled=false")}
 * keeps {@code SettlementWorker}'s daily-batch {@code @Scheduled}
 * from firing mid-test. {@code @DirtiesContext} rebuilds the context
 * per test class.</p>
 *
 * <p>The {@link com.fintech.payment.service.AuditService} collaborator
 * is auto-discovered by the {@code @SpringBootTest} component scan;
 * no explicit autowire reference is needed in this test class
 * because no test method calls it directly.</p>
 */
@SpringBootTest
// @SpringBootTest activates AuditingConfig (@EnableJpaAuditing) so
// @CreatedDate populates AuditLog.createdAt in seeded fixtures —
// @DataJpaTest's slice swallowed the @Import'd AuditingConfig in
// earlier iterations, surfacing as a "NULL not allowed for column
// CREATED_AT" constraint failure at saveAndFlush. Scheduling is
// disabled to keep SettlementWorker's daily-batch @Scheduled from
// firing mid-test. ReconciliationReportService and AuditService are
// auto-discovered as @Service beans by the application's component scan.
@TestPropertySource(properties = "spring.scheduling.enabled=false")
@DirtiesContext
@DisplayName("ReconciliationReportService — window edges + future-date 422")
class ReconciliationReportServiceTest {

    /** Pinned "now": 2026-06-15T12:00:00Z. Anything before UTC midnight on this date is "today"; anything after is future. */
    private static final Instant PINNED_NOW = Instant.parse("2026-06-15T12:00:00Z");

    private static final LocalDate TODAY = LocalDate.parse("2026-06-15");
    private static final LocalDate YESTERDAY = LocalDate.parse("2026-06-14");
    private static final LocalDate WEEKS_AGO = LocalDate.parse("2026-06-01");

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ReconciliationReportService service;

    /**
     * Application-code clock — used by {@code ReconciliationReportService}
     * for {@code LocalDate.now(clock)} math on the future-date 422 path.
     */
    @MockitoBean
    private Clock clock;

    /**
     * Phase 7 / §12.7.1: the spring-data-internal AuditingHandler pulls
     * a {@link DateTimeProvider} bean at construction time and reads
     * {@code getNow()} on each {@code @PrePersist} event. {@code @MockitoBean}
     * is the closest-test-envelope match to production wiring, but
     * Phase-7.x v1 + v2 confirmed that neither {@code @MockitoBean} nor an
     * explicit {@code @TestConfiguration @Primary} bean delivers the
     * staged {@link java.time.Instant} to {@code @CreatedDate} on a per-row
     * basis. Root-cause hypothesis: AuditingHandler captures the
     * reference at construction; subsequent bean replacement does not
     * propagate; the handler falls back to {@code Clock.systemUTC().instant()},
     * which the window-edge tests treat as OUT-of-window
     * (test pinned at 2026-06-15; wall-clock NOW falls outside
     * [2026-06-15T00:00Z, 2026-06-16T00:00Z)) → "expected X but was 0L".
     * Forward-flagged to SRS §12.7.1.1 for Phase 7.x.2.
     */
    @MockitoBean
    private DateTimeProvider dateTimeProvider;

    @BeforeEach
    void reset() {
        auditLogRepository.deleteAll();
        when(clock.instant()).thenReturn(PINNED_NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(dateTimeProvider.getNow()).thenAnswer(inv -> Optional.empty());
    }

    @Test
    @DisplayName("future date raises ReconciliationReportUnavailableException — 422 envelope via GlobalExceptionHandler")
    void future_date_throws_reconciliation_unavailable() {
        LocalDate tomorrow = TODAY.plusDays(1);
        assertThatThrownBy(() -> service.generateReport(tomorrow))
                .isInstanceOf(ReconciliationReportUnavailableException.class)
                .hasMessageContaining("future date")
                .hasMessageContaining(tomorrow.toString());
    }

    @Test
    @DisplayName("ancient date — window is correctly far-past and not zero by default")
    void distant_past_date_with_no_events_returns_zero_totals() {
        var report = service.generateReport(WEEKS_AGO);
        assertThat(report.totalAuditEvents()).isZero();
        assertThat(report.byEntityType()).isEmpty();
        assertThat(report.byAction()).isEmpty();
        assertThat(report.fromCache()).as("Phase 5 KISS — fromCache always false").isFalse();
    }

    @Disabled("§12.7.1 + §12.7.1.1 SRS drift — see SRS.md §12.7.1.1 for the full Phase 7.x v1+v2 attempt trail and forward-flag to Phase 7.x.2.")
    @Test
    @DisplayName("today's window is a half-open [T00:00Z, T+1d 00:00Z) range")
    void today_window_captures_only_today_after_midnight_utc() {
        // One event today (06-15 09:00 UTC) + one yesterday (06-14 23:00 UTC) + one weeks_ago (06-01)
        seed("PAYMENT", AuditAction.CREATED, "P-1", Instant.parse("2026-06-15T09:00:00Z"));
        seed("PAYMENT", AuditAction.CREATED, "P-2", Instant.parse("2026-06-14T23:00:00Z"));
        seed("PAYMENT", AuditAction.CREATED, "P-3", Instant.parse("2026-06-01T00:00:00Z"));

        var report = service.generateReport(TODAY);
        assertThat(report.date()).isEqualTo(TODAY);
        assertThat(report.totalAuditEvents())
                .as("only the today-row falls inside [06-15T00:00Z, 06-16T00:00Z)")
                .isEqualTo(1L);
        assertThat(report.byEntityType()).containsOnlyKeys("PAYMENT");
        assertThat(report.byAction()).containsOnlyKeys("CREATED");
    }

    @Disabled("§12.7.1 + §12.7.1.1 SRS drift — see SRS.md §12.7.1.1 for diagnosis trail across Phase 7.x v1+v2 attempts.")
    @Test
    @DisplayName("yesterday's window is disjoint from today's")
    void yesterday_window_captures_only_yesterday_day() {
        seed("PAYMENT", AuditAction.CREATED, "P-today", Instant.parse("2026-06-15T09:00:00Z"));
        seed("PAYMENT", AuditAction.CREATED, "P-yest", Instant.parse("2026-06-14T23:00:00Z"));
        seed("PAYMENT", AuditAction.CREATED, "P-days-past", Instant.parse("2026-06-10T00:00:00Z"));

        var report = service.generateReport(YESTERDAY);
        assertThat(report.date()).isEqualTo(YESTERDAY);
        assertThat(report.totalAuditEvents()).isEqualTo(1L);
    }

    @Disabled("§12.7.1 + §12.7.1.1 SRS drift — see SRS.md §12.7.1.1 for diagnosis trail across Phase 7.x v1+v2 attempts.")
    @Test
    @DisplayName("byEntityType + byAction groupby aggregates correctly across multiple entity types")
    void groupbys_are_correctly_partitioned() {
        seed("PAYMENT", AuditAction.CREATED, "P-1", Instant.parse("2026-06-15T01:00:00Z"));
        seed("PAYMENT", AuditAction.CREATED, "P-2", Instant.parse("2026-06-15T02:00:00Z"));
        seed("PAYMENT", AuditAction.REVERSED, "P-3", Instant.parse("2026-06-15T03:00:00Z"));
        seed("ACCOUNT", AuditAction.STATUS_CHANGE, "A-1", Instant.parse("2026-06-15T04:00:00Z"));
        seed("SETTLEMENT_BATCH", AuditAction.BATCH_SETTLED, "S-1", Instant.parse("2026-06-15T05:00:00Z"));
        seed("SETTLEMENT_BATCH", AuditAction.BATCH_SETTLED, "S-2", Instant.parse("2026-06-15T06:00:00Z"));

        var report = service.generateReport(TODAY);
        assertThat(report.totalAuditEvents()).isEqualTo(6L);
        assertThat(report.byEntityType())
                .containsEntry("PAYMENT", 3L)
                .containsEntry("ACCOUNT", 1L)
                .containsEntry("SETTLEMENT_BATCH", 2L);
        assertThat(report.byAction())
                .containsEntry("CREATED", 2L)
                .containsEntry("REVERSED", 1L)
                .containsEntry("STATUS_CHANGE", 1L)
                .containsEntry("BATCH_SETTLED", 2L);
        assertThat(report.windowStart()).isEqualTo("2026-06-15T00:00:00Z");
        assertThat(report.windowEnd()).isEqualTo("2026-06-16T00:00:00Z");
    }

    /**
     * Stages {@link DateTimeProvider#getNow()} so the next
     * {@code auditLogRepository.save(...)} invokes the listener with the
     * supplied {@code at} as the {@code @CreatedDate} column value.
     * Stages a single return — call once per seed.
     */
    private void seed(String entityType, AuditAction action, String entityId, Instant at) {
        when(dateTimeProvider.getNow()).thenReturn(Optional.of((TemporalAccessor) at));
        AuditLog row = new AuditLog(
                entityType,
                entityId == null ? null : safeUuid(entityId),
                action,
                /* oldValue */ null,
                /* newValue */ null,
                /* performedBy */ "system");
        auditLogRepository.saveAndFlush(row);
    }

    private static UUID safeUuid(String label) {
        // Deterministic but distinct UUIDs from string labels — keeps
        // the test seed data readable in the audit-log rows.
        return UUID.nameUUIDFromBytes(label.getBytes());
    }
}
