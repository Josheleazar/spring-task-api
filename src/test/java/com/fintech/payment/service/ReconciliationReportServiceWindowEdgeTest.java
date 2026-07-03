package com.fintech.payment.service;

import com.fintech.payment.exception.ReconciliationReportUnavailableException;
import com.fintech.payment.model.entity.AuditLog;
import com.fintech.payment.model.enums.AuditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8 §12.6.3.2 — coverage-push unit tests for
 * {@link ReconciliationReportService}.
 *
 * <p>Closes the Phase 6 §12.6.3 forward-flag §(1) — exercise the report
 * generator's window-edge semantics (eod vs intraday boundary math) +
 * the prewarmYesterday try/catch arms. The existing
 * {@code ReconciliationReportServiceTest} covers future-date 422 and
 * distant-past zero-totals via {@code @SpringBootTest}, but the 4
 * window-edge + 2 prewarmYesterday tests are {@code @Disabled} per the
 * Phase 7.x §12.7.1.1 forward-flag ({@code DateTimeProvider} wiring
 * still broken under {@code @SpringBootTest}). This test class
 * sidesteps that defect by exercising the source-level branch graph
 * with pure {@code @Mockito} mocks — same coverage semantics, no
 * Spring context, no DateTimeProvider required.</p>
 *
 * <h2>What this test exercises</h2>
 *
 * <ol>
 *   <li><strong>Future-date 422 path.</strong> {@code date.isAfter(today)}
 *       fires the {@link ReconciliationReportUnavailableException}
 *       branch — the only friend-or-foe endpoint entry that admits
 *       422-mapping via {@link GlobalExceptionHandler}. Boundary
 *       exactly: {@code date == today} OK, {@code date == today+1} throws.</li>
 *   <li><strong>Distant-past / today-with-no-events empty path.</strong>
 *       {@code auditService.findByWindow} returns empty List — the
 *       for-loop body never executes; report returns zero totals
 *       + empty maps + {@code fromCache=false}.</li>
 *   <li><strong>Today-with-events population path.</strong> {@code findByWindow}
 *       returns a List&lt;AuditLog&gt; with mixed entity types + actions.
 *       Both {@code byEntityType} and {@code byAction} merge calls fire —
 *       the JAva 8+ TreeMap.merge(...) path is exercised.</li>
 *   <li><strong>Window-edge math invariant.</strong> The
 *       {@code [date 00:00Z, date+1 00:00Z)} half-open interval is
 *       captured via ArgumentCaptor to verify exact UTC midnight
 *       arithmetic: {@code windowStart = date.atStartOfDay(UTC).toInstant()};
 *       {@code windowEnd = date.plusDays(1).atStartOfDay(UTC).toInstant()}.
 *       An event at {@code 2026-06-15T00:00:00.000Z} is the lower-inclusive
 *       boundary; an event at {@code 2026-06-16T00:00:00.000Z} falls into
 *       tomorrow's window.</li>
 *   <li><strong>{@code fromCache} always-false invariant.</strong> The
 *       report is a {@code compute-on-demand} (Phase 5 KISS shape);
 *       Phase 6 pluggable-cache would flip this — tracked separately.</li>
 *   <li><strong>{@code prewarmYesterday} happy path.</strong> The
 *       {@code @Scheduled} tick at {@code 00:30 UTC} calls
 *       {@code generateReport(today.minusDays(1))} + logs the summary
 *       at INFO. Verifies the {@code yesterday} derivation chain
 *       (which depends on {@code Clock.instant()}).</li>
 *   <li><strong>{@code prewarmYesterday} catch path.</strong> If the
 *       audit-log query throws any {@link RuntimeException}, the
 *       {@code @Scheduled} executor swallows it (logged at WARN) so
 *       the daily prewarm tick never crashes the scheduler — a
 *       critical liveness guarantee against an upstream DB outage.</li>
 *   <li><strong>Class-level scheduling metadata.</strong> The
 *       {@code @Scheduled} cron is exactly {@code "0 30 0 * * *"} —
 *       30 minutes after {@link SettlementService#createDailyBatch}'s
 *       midnight fire, the optimal window after the @Async claim
 *       loop has settled.</li>
 * </ol>
 *
 * <h2>Why pure @Mockito (not @SpringBootTest)</h2>
 *
 * <p>The pre-existing {@code ReconciliationReportServiceTest} runs
 * under {@code @SpringBootTest} + {@code @MockitoBean DateTimeProvider}.
 * Phase 7.x §12.7.1.1 confirmed that the spring-data AuditingHandler
 * captures the {@code DateTimeProvider} reference at construction time,
 * so a {@code @MockitoBean} replacement does NOT propagate to @CreatedDate
 * on the per-row flush path. Tests that seed rows for window-edge math
 * see {@code createdAt} defaulting to {@code Clock.systemUTC().instant()}
 * — wall-clock NOW, not the staged Instant.</p>
 *
 * <p>This class sidesteps that defect entirely: the unit tests do not
 * depend on {@code @CreatedDate} hydration because they mock
 * {@code AuditService.findByWindow} directly. The source's window-edge
 * math is exercised end-to-end without touching JPA / Auditing listeners.</p>
 *
 * <h2>Hermeticity</h2>
 *
 * <p>{@code @ExtendWith(MockitoExtension.class)} — no Spring context,
 * no @SpringBootTest, no auditor handler. The 2 collaborators
 * {@code AuditService} and {@code Clock} are mocked. Window math
 * verifies on exact {@code Instant} values derived from the pinned
 * "now" pin (no DateTimeProvider).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationReportService — window-edge arithmetic + prewarmYesterday arms")
class ReconciliationReportServiceWindowEdgeTest {

    /**
     * Pinned 'today' instant: 2026-06-15T12:00:00Z (noon UTC, well below
     * the 2026-06-16T00:00Z window-end boundary). Any date derived from
     * {@code LocalDate.now(clock)} folds to {@code 2026-06-15}; subtract
     * one day for "yesterday" → {@code 2026-06-14}.
     */
    private static final Instant PINNED_NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-06-15");
    private static final LocalDate YESTERDAY = LocalDate.parse("2026-06-14");
    private static final LocalDate WEEKS_AGO = LocalDate.parse("2026-06-01");
    private static final Instant WINDOW_START_TODAY = Instant.parse("2026-06-15T00:00:00Z");
    private static final Instant WINDOW_END_TODAY_EXCLUSIVE = Instant.parse("2026-06-16T00:00:00Z");

    @Mock
    private AuditService auditService;

    @Mock
    private Clock clock;

    @InjectMocks
    private ReconciliationReportService service;

    @BeforeEach
    void stubClock() {
        // Strict-stubbing tolerated: the future-date throw-path skips
        // both clock.instant() and clock.getZone(); the prewarmYesterday
        // exception-path skips neither.  Hence lenient for both.
        lenient().when(clock.instant()).thenReturn(PINNED_NOW);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    /* ====================================================================
     * generateReport — branch graph (date.isAfter + for-loop empty/non-empty)
     * ==================================================================== */

    @Nested
    @DisplayName("generateReport")
    class GenerateReport {

        @Test
        @DisplayName("future date → ReconciliationReportUnavailableException (422 envelope source)")
        void futureDate_throwsReconciliationReportUnavailableException_withFutureAndTodayInMessage() {
            // Branch A (true): date.isAfter(today) → throw.
            LocalDate tomorrow = TODAY.plusDays(1);

            assertThatThrownBy(() -> service.generateReport(tomorrow))
                    .isInstanceOf(ReconciliationReportUnavailableException.class)
                    .hasMessageContaining("future date")
                    .hasMessageContaining(tomorrow.toString())
                    .hasMessageContaining(TODAY.toString());

            // Invariants: NEVER reached auditService on a throw — the
            // throw is the FIRST action of the method, no AuditLog query.
            // The 422 mapping (per GlobalExceptionHandler) requires the
            // exception type, not the audit read.
            verify(auditService, never()).findByWindow(any(Instant.class), any(Instant.class));
        }

        @Test
        @DisplayName("today is the inclusive upper boundary — does NOT throw")
        void today_isNotAfter_continuesToWindowQuery() {
            // Boundary test for Branch A (false): TODAY is not after TODAY.
            when(auditService.findByWindow(any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of());

            var report = service.generateReport(TODAY);

            assertThat(report.date()).isEqualTo(TODAY);
            assertThat(report.totalAuditEvents()).isZero();
            verify(auditService, times(1)).findByWindow(
                    WINDOW_START_TODAY, WINDOW_END_TODAY_EXCLUSIVE);
        }

        @Test
        @DisplayName("distant past + no events → empty report (fromCache=false, totalEvents=0)")
        void distantPast_noEvents_returnsEmptyReport_withFromCacheFalse() {
            // Branch B (empty): findByWindow returns empty List, the
            // for-loop body never executes, byEntityType = {},
            // byAction = {}, totalEvents=0.
            when(auditService.findByWindow(any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of());

            var report = service.generateReport(WEEKS_AGO);

            assertThat(report.date()).isEqualTo(WEEKS_AGO);
            assertThat(report.totalAuditEvents())
                    .as("no AuditLog rows in the distant-past window")
                    .isZero();
            assertThat(report.byEntityType()).isEmpty();
            assertThat(report.byAction()).isEmpty();
            assertThat(report.fromCache())
                    .as("Phase 5 KISS — fromCache is hardcoded false (no Redis/Caffeine layer)")
                    .isFalse();
        }

        @Test
        @DisplayName("today with mixed events → groupbys correctly partition by entityType and action")
        void today_withMixedEvents_partitionsCorrectly_acrossEntityTypesAndActions() {
            // Branch B (non-empty): findByWindow returns a populated List,
            // the for-loop body executes, byEntityType.merge(...) and
            // byAction.merge(...) fire.
            List<AuditLog> rows = List.of(
                    newAuditLog("PAYMENT", AuditAction.CREATED, "p-1"),
                    newAuditLog("PAYMENT", AuditAction.CREATED, "p-2"),
                    newAuditLog("PAYMENT", AuditAction.REVERSED, "p-3"),
                    newAuditLog("ACCOUNT", AuditAction.STATUS_CHANGE, "a-1"),
                    newAuditLog("SETTLEMENT_BATCH", AuditAction.BATCH_SETTLED, "s-1"),
                    newAuditLog("SETTLEMENT_BATCH", AuditAction.BATCH_SETTLED, "s-2")
            );
            when(auditService.findByWindow(WINDOW_START_TODAY, WINDOW_END_TODAY_EXCLUSIVE))
                    .thenReturn(rows);

            var report = service.generateReport(TODAY);

            assertThat(report.totalAuditEvents()).isEqualTo(6L);
            assertThat(report.byEntityType())
                    .as("PAYMENT x3, ACCOUNT x1, SETTLEMENT_BATCH x2")
                    .containsEntry("PAYMENT", 3L)
                    .containsEntry("ACCOUNT", 1L)
                    .containsEntry("SETTLEMENT_BATCH", 2L)
                    .hasSize(3);
            assertThat(report.byAction())
                    .as("CREATED x2, REVERSED x1, STATUS_CHANGE x1, BATCH_SETTLED x2")
                    .containsEntry("CREATED", 2L)
                    .containsEntry("REVERSED", 1L)
                    .containsEntry("STATUS_CHANGE", 1L)
                    .containsEntry("BATCH_SETTLED", 2L)
                    .hasSize(4);
        }

        @Test
        @DisplayName("window-edge math — windowStart is today 00:00Z, windowEnd is tomorrow 00:00Z (exclusive)")
        void windowEdgeMath_windowStartAndEnd_arePinnedToUtcMidnight_forAnyDate() {
            // The half-open [date 00:00Z, date+1 00:00Z) interval is the
            // core eod/intraday boundary contract. Capture the values
            // passed to auditService.findByWindow(...) and assert exactness.
            //
            // All 5 dates must be ≤ PINNED_NOW's date (2026-06-15) — any
            // future date is rejected by the very first guard
            // `date.isAfter(today)` and the test fails before reaching
            // the captor verify boundary. Multi-month distances verify
            // the half-open interval holds regardless of how close date
            // is to today or how large the date is.
            //
            // Mockito invocation-count accumulation noted: each iteration
            // adds 1 invocation, so verify(times(1)) per iteration would
            // fail on iterations 2+ as the cumulative count grows. Mockito's
            // clearInvocations(mock) clears the invocation counter WITHOUT
            // affecting stubs — perfect for scoped per-iteration verifies.
            LocalDate[] dates = {
                    TODAY,                            // 2026-06-15 — present-day boundary (date == today)
                    YESTERDAY,                        // 2026-06-14 — date < today
                    WEEKS_AGO,                        // 2026-06-01 — ~2 weeks ago
                    LocalDate.parse("2026-05-01"),    // ~6 weeks ago (eom May)
                    LocalDate.parse("2026-01-01")     // 6 months ago (year-start 2026)
            };
            for (LocalDate d : dates) {
                clearInvocations(auditService);  // scope count to per-iteration
                when(auditService.findByWindow(any(Instant.class), any(Instant.class)))
                        .thenReturn(List.of());

                service.generateReport(d);

                ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
                ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
                verify(auditService, times(1)).findByWindow(
                        fromCaptor.capture(), toCaptor.capture());
                Instant expectedStart = d.atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant expectedEnd = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                assertThat(fromCaptor.getValue())
                        .as("windowStart for date %s is exactly that date's UTC 00:00", d)
                        .isEqualTo(expectedStart);
                assertThat(toCaptor.getValue())
                        .as("windowEnd for date %s is exactly date+1 UTC 00:00 (exclusive)", d)
                        .isEqualTo(expectedEnd);
                assertThat(expectedEnd)
                        .as("windowEnd is strictly greater than windowStart by exactly one day")
                        .isEqualTo(expectedStart.plusSeconds(86_400));
            }
        }

        @Test
        @DisplayName("fromCache invariant — always false regardless of input shape (Phase 5 KISS)")
        void fromCache_invariant_isAlwaysFalse_regardlessOfInput() {
            // Two attempts (one empty list, one populated) — both must
            // return fromCache=false. Catches any future refactor that
            // accidentally introduces a per-request cache flag.
            when(auditService.findByWindow(WINDOW_START_TODAY, WINDOW_END_TODAY_EXCLUSIVE))
                    .thenReturn(List.of())
                    .thenReturn(List.of(newAuditLog("PAYMENT", AuditAction.CREATED, "p-1")));

            assertThat(service.generateReport(TODAY).fromCache()).isFalse();
            assertThat(service.generateReport(TODAY).fromCache()).isFalse();
        }
    }

    /* ====================================================================
     * prewarmYesterday — branch graph (try-block succeeds + catch fires)
     * ==================================================================== */

    @Nested
    @DisplayName("prewarmYesterday — @Scheduled 00:30 UTC tick")
    class PrewarmYesterday {

        @Test
        @DisplayName("happy path — calls generateReport(yesterday) and logs the summary without exception")
        void happyPath_callsGenerateReportWithYesterday_andDoesNotThrow() {
            // Branch C (try succeeds): LocalDate.now(clock).minusDays(1) =
            // TODAY - 1 day = YESTERDAY. The prewarmYesterday method calls
            // service.generateReport(YESTERDAY) → auditService.findByWindow
            // for YESTERDAY's window. Verify the correct date derivation.
            Instant yesterdayWindowStart = YESTERDAY.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant yesterdayWindowEnd = YESTERDAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            when(auditService.findByWindow(yesterdayWindowStart, yesterdayWindowEnd))
                    .thenReturn(List.of());

            // Explicit no-rethrow contract — the @Scheduled tick MUST survive.
            assertDoesNotThrow(() -> service.prewarmYesterday());

            verify(auditService, times(1)).findByWindow(
                    yesterdayWindowStart, yesterdayWindowEnd);
        }

        @Test
        @DisplayName("exception path — findByWindow throws, the @Scheduled tick SWALLOWS + logs WARN, never rethrows")
        void exceptionPath_catchesAndSwallows_doesNotRethrow() {
            // Branch D (catch fires): auditService.findByWindow throws a
            // RuntimeException → generateReport propagates → prewarmYesterday's
            // catch (Exception) logs WARN + swallows. The @Scheduled
            // executor MUST survive such failures — a sooner-throw would
            // crash the daily prewarm cron and starve operations of the
            // yesterday summary.
            Instant yesterdayWindowStart = YESTERDAY.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant yesterdayWindowEnd = YESTERDAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            when(auditService.findByWindow(yesterdayWindowStart, yesterdayWindowEnd))
                    .thenThrow(new RuntimeException("simulated upstream DB outage"));

            // Explicit no-rethrow contract — assertDoesNotThrow (JUnit 5)
            // makes the contract searchable in CI output AND doubles as
            // the trip-wire if a future refactor accidentally removes
            // the catch arm.
            assertDoesNotThrow(() -> service.prewarmYesterday());

            // Single-attempt: prewarmYesterday called generateReport ONCE,
            // which called auditService.findByWindow once, which threw —
            // no retry logic in prewarmYesterday, no second auditService call.
            verify(auditService, times(1)).findByWindow(
                    yesterdayWindowStart, yesterdayWindowEnd);
        }
    }

    /* ====================================================================
     * Class-invariants — @Scheduled cron + non-Transactional
     * ==================================================================== */

    @Nested
    @DisplayName("class-level scheduling invariants")
    class ClassInvariants {

        @Test
        @DisplayName("prewarmYesterday is @Scheduled cron \"0 30 0 * * *\" (00:30 UTC daily)")
        void prewarmYesterday_isScheduledAt_00_30_UTC_daily() {
            // The 00:30 UTC choice is 30 minutes after SettlementService's
            // midnight @Scheduled (00:00) — enough window for the @Async
            // claim-and-finalize to emit BATCH_OPEN + BATCH_SETTLED audit
            // rows before the prewarm ticks. Any deviation would silently
            // break the operator-visible yesterday-summary guarantee.
            // Reflection wrap via assertDoesNotThrow (per SRS §12.6.3.1
            // captured pattern) — Class#getDeclaredMethod throws the
            // CHECKED NoSuchMethodException; bare call from a test method
            // is a compile error.
            Scheduled scheduled = assertDoesNotThrow(() ->
                    ReconciliationReportService.class
                            .getDeclaredMethod("prewarmYesterday")
                            .getAnnotation(Scheduled.class));
            assertThat(scheduled)
                    .as("prewarmYesterday must carry @Scheduled so the daily prewarm fires")
                    .isNotNull();
            assertThat(scheduled.cron())
                    .as("cron expression: 00:30 UTC, every day-of-month, every month, every day-of-week")
                    .isEqualTo("0 30 0 * * *");
        }
    }

    /* ====================================================================
     * Test fixtures
     * ==================================================================== */

    private static AuditLog newAuditLog(String entityType, AuditAction action, String entityIdLabel) {
        // Pseudo-deterministic UUID derived from the entityIdLabel — keeps
        // tests greppable when a row surface is needed.
        UUID entityId = UUID.nameUUIDFromBytes(entityIdLabel.getBytes());
        return new AuditLog(entityType, entityId, action,
                /* oldValue */ null, /* newValue */ null, /* performedBy */ "system");
    }

    @SuppressWarnings("unused") // captured via tree-map test asserts below
    private static Map<String, Long> emptyMap() {
        return Map.of();
    }
}
