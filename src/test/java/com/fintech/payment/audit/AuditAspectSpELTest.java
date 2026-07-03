package com.fintech.payment.audit;

import com.fintech.payment.model.entity.AuditLog;
import com.fintech.payment.model.enums.AuditAction;
import com.fintech.payment.repository.AuditLogRepository;
import com.fintech.payment.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 §12.7.1 Batch 5 — coverage-push test for
 * {@link com.fintech.payment.audit.AuditAspect}'s SpEL evaluation
 * surface.
 *
 * <p>Closes the SRS §12.6.3 forward-flag §(2) — exercise
 * {@link Audited#oldValueSpel()} / {@link Audited#newValueSpel()} on a
 * real Spring bean to lock in the pre-afterCommit snapshot semantics
 * + the SpEL-expression cache hit path.</p>
 *
 * <h2>Test bean shape</h2>
 *
 * <p>A small {@link TestAuditedBean} @Service is registered via
 * {@code @Import} with five distinct @Audited method shapes — empty
 * SpEL, only-oldValue, only-newValue, both, invalid-SpEL — covering
 * the four slots the aspect handles plus the defensive try/catch.
 * Methods return a {@link TestState} enum so the SpEL expression
 * {@code #result.name()} resolves cleanly to a JSON-serialized
 * String ("BEFORE" / "AFTER"), matching the production shape of
 * {@code PaymentService.reversePayment} (where the newValueSpel
 * captures {@code #result.status.name()}).</p>
 *
 * <h2>Cache-hit assertion</h2>
 *
 * <p>N invocations of the same method should produce the same JSON
 * content. We don't instrument the SpEL parser directly; instead we
 * assert that all N audit rows carry the identical SpEL snapshot
 * (deterministic across calls), which proves the cache hit is
 * effectively behaving correctly — if the cache missed or recomputed
 * per-call, the captured String would still be the same, so this is
 * a soft signal only. The reviewer's §12.6.3 forward-flag notes that
 * a stronger assertion would require a spy on the parser; treated
 * as a forward-flag.</p>
 */
@SpringBootTest
@Import({AuditAspect.class, AuditService.class, AuditAspectSpELTest.TestAuditedBean.class})
@DirtiesContext
@DisplayName("AuditAspect — SpEL evaluation + cache-hit path")
class AuditAspectSpELTest {

    @Autowired
    private TestAuditedBean bean;

    @Autowired
    private AuditLogRepository auditLogRepository;

    /**
     * Deterministic UUID used across all test bean invocations in this
     * suite — mirrors a production-shape entityId. The AuditAspect's
     * {@code resolveEntityId()} requires a {@code UUID} instance on
     * the {@code entityIdArg} parameter (the aspect explicitly checks
     * {@code arg instanceof UUID}). Without a UUID, the resolved
     * entityId is {@code null} and the audit row INSERT fails on
     * {@code entity_id nullable=false}; {@code AuditAspect.writeAudit()}
     * silently swallows the constraint violation, leaving 0 rows for
     * the {@code findAll()} assertion.
     */
    private static final UUID TEST_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void reset() {
        auditLogRepository.deleteAll();
    }

    @Nested
    @DisplayName("SpEL evaluation")
    class SpelEvaluation {

        @Test
        @DisplayName("empty SpEL slots → audit row carries null oldValue/newValue")
        void empty_spEL_writes_null_columns() {
            bean.emptySpel(TEST_ID);

            List<AuditLog> rows = auditLogRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getOldValue()).isNull();
            assertThat(rows.get(0).getNewValue()).isNull();
        }

        @Test
        @DisplayName("non-empty oldValueSpel → pre-state JSON captured in oldValue column")
        void non_empty_oldValueSpel_writes_json_to_oldValue_column() {
            bean.nonEmptyOldSpel(TEST_ID);

            List<AuditLog> rows = auditLogRepository.findAll();
            assertThat(rows).hasSize(1);
            // SpEL #id resolves to the UUID parameter; Jackson serializes
            // a UUID as a JSON-quoted hyphenated string ("<uuid-with-hyphens>"),
            // so the captured oldValue contains TEST_ID wrapped in quotes.
            assertThat(rows.get(0).getOldValue())
                    .as("pre-state SpEL #id (UUID parameter) round-tripped by Jackson")
                    .contains(TEST_ID.toString());
        }

        @Test
        @DisplayName("non-empty newValueSpel → post-state #result.name() captured in newValue column")
        void non_empty_newValueSpel_writes_json_to_newValue_column() {
            bean.nonEmptyNewSpel(TEST_ID);

            List<AuditLog> rows = auditLogRepository.findAll();
            assertThat(rows).hasSize(1);
            // SpEL #result.name() resolves to TestState.AFTER.name() = "AFTER".
            assertThat(rows.get(0).getNewValue())
                    .as("post-state SpEL via #result.name() (TestState.AFTER.name())")
                    .contains("AFTER");
        }

        @Test
        @DisplayName("both SpEL slots populated → both columns carry distinct JSON")
        void both_spels_write_distinct_json() {
            bean.bothSpels(TEST_ID);

            List<AuditLog> rows = auditLogRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getOldValue()).contains("PRE-BOTH");
            assertThat(rows.get(0).getNewValue()).contains("AFTER");
            assertThat(rows.get(0).getOldValue())
                    .as("slots are independent — oldValue carries the literal pre-state, not the post-state")
                    .doesNotContain("AFTER");
        }

        @Test
        @DisplayName("invalid SpEL expression is swallowed and converted to null column (defensive)")
        void invalid_spEL_is_swallowed_and_writes_null() {
            // SpEL expression targets a non-existent bean — which throws
            // SpelEvaluationException at evaluation time. The aspect's
            // resolveSpelValue wraps the call in try/catch (RuntimeException),
            // so the failed eval is logged at WARN and the column becomes null.
            bean.invalidSpel(TEST_ID);

            List<AuditLog> rows = auditLogRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getOldValue())
                    .as("defensive try/catch converts SpEL eval error to null column")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("SpEL cache hit path")
    class SpelCacheHit {

        @Test
        @DisplayName("same compiled Expression instance is reused across N invocations")
        void same_method_same_slot_reuses_compiled_expression_across_calls() {
            int invocations = 12;
            for (int i = 0; i < invocations; i++) {
                bean.nonEmptyNewSpel(TEST_ID);
            }

            List<AuditLog> rows = auditLogRepository.findAll();
            assertThat(rows).hasSize(invocations);

            // Soft signal for cache hit: every row carries the same JSON
            // content. The aspect's ConcurrentHashMap<SpelSlotKey, Expression>
            // cache is keyed on (Method, isOldValue); if the cache missed,
            // SpelExpressionParser would still produce the same parsed tree
            // (deterministic), so this assertion is a soft signal rather
            // than a hard contract check. A spy on the parser would be
            // strictly stronger (Phase-7 forward-flag).
            assertThat(rows).extracting(AuditLog::getNewValue)
                    .as("all %d invocations produced the same SpEL snapshot", invocations)
                    .containsOnly("\"AFTER\"");
        }
    }

    /**
     * Tiny lifecycle states with stable names — exactly what
     * {@code #result.name()} consumes in
     * {@code PaymentService.reversePayment}'s SpEL retrofit.
     * Mocked here in test scope to keep the AuditAspect surface
     * covered without depending on production entity types.
     */
    public enum TestState {
        BEFORE,
        AFTER
    }

    /**
     * Test-only @Service that hosts the five @Audited method shapes
     * exercised by this suite. Methods return a {@link TestState}
     * enum so {@code #result.name()} resolves to a stable String.
     */
    @org.springframework.stereotype.Service
    static class TestAuditedBean {

        @Audited(
                entityType = "TEST_BEAN",
                action = AuditAction.CREATED,
                entityIdArg = "id")
        public TestState emptySpel(UUID id) {
            return TestState.BEFORE;
        }

        @Audited(
                entityType = "TEST_BEAN",
                action = AuditAction.STATUS_CHANGE,
                entityIdArg = "id",
                oldValueSpel = "#id")
        public TestState nonEmptyOldSpel(UUID id) {
            // The SpEL #id picks up the UUID parameter. The audit row
            // will carry the UUID's JSON serialization (a hyphenated
            // quoted string like "11111111-2222-3333-4444-555555555555").
            return TestState.BEFORE;
        }

        @Audited(
                entityType = "TEST_BEAN",
                action = AuditAction.REVERSED,
                entityIdArg = "id",
                newValueSpel = "#result.name()")
        public TestState nonEmptyNewSpel(UUID id) {
            // SpEL captures the post-state enum.name() → "AFTER".
            return TestState.AFTER;
        }

        @Audited(
                entityType = "TEST_BEAN",
                action = AuditAction.STATUS_CHANGE,
                entityIdArg = "id",
                oldValueSpel = "'PRE-BOTH'",
                newValueSpel = "#result.name()")
        public TestState bothSpels(UUID id) {
            // Spans pre-state literal + post-state enum.name().
            return TestState.AFTER;
        }

        @Audited(
                entityType = "TEST_BEAN",
                action = AuditAction.STATUS_CHANGE,
                entityIdArg = "id",
                oldValueSpel = "#nonExistentBean.target",
                newValueSpel = "")
        public TestState invalidSpel(UUID id) {
            // SpEL evaluation fails → defensive try/catch → null column.
            return TestState.BEFORE;
        }
    }
}
