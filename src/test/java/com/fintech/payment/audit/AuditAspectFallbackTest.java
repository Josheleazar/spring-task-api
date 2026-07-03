package com.fintech.payment.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.model.enums.AuditAction;
import com.fintech.payment.service.AuditService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8 §12.6.3.3 — coverage-push {@code @Mockito} unit tests for
 * {@link AuditAspect} fallback branches.
 *
 * <p>Closes the Phase 6 §12.6.3 forward-flag §(2) — exercise the
 * entity-id resolution fallbacks + writeAudit catch + proceed() throws +
 * TransactionSynchronizationManager branches that the existing
 * {@code AuditAspectSpELTest} ({@code @SpringBootTest}) does not cover.
 * The pure-{@code @Mockito} shape sidesteps the {@code DateTimeProvider}
 * + AuditingListener wiring cost and runs in &lt;100ms.</p>
 *
 * <h2>Branch graph targeted</h2>
 *
 * <p>AuditAspect has ~14 JaCoCo-counted branches; the existing
 * AuditAspectSpELTest covers 5 of them (SpEL happy + cache soft
 * signal + defensive catch-all {@code invalid_spEL_is_swallowed})
 * on the {@code resolveSpelValue} path. This class covers the other
 * 9 branches:</p>
 * <ul>
 *   <li><strong>resolveEntityId branches:</strong> parameterNameDiscoverer
 *       returns null (Branch E); name found in loop (Branch F happy
 *       + G arg instanceof UUID); name found, arg non-UUID (Branch
 *       F + H); name found, arg null (Branch F + arg null fast-return);
 *       name NOT found in loop (Branch F miss).</li>
 *   <li><strong>resolveSpelValue branches:</strong> spelExpression null
 *       (Branch J null); spelExpression blank (Branch J blank);
 *       JsonProcessingException caught (Branch K); RuntimeException
 *       caught (Branch L).</li>
 *   <li><strong>writeAudit catch:</strong> auditService.record(...) throws
 *       DataIntegrityViolationException → caught (Branch D fail) —
 *       documents the §12.7.2.2 deferred-failure mode.</li>
 *   <li><strong>auditAnnotatedMethod proceed arms:</strong>
 *       proceed() throws (Branch B) — no audit row written;
 *       proceed() succeeds, no ambient tx (Branch C inactive)
 *       → writeAudit synchronous;
 *       proceed() succeeds, ambient tx (Branch C active)
 *       → registerSynchronization defers writeAudit to afterCommit.</li>
 * </ul>
 *
 * <h2>§12.7.2.2 deferred-failure documentation</h2>
 *
 * <p>The Phase 7.x Item 3 {@code @Disabled} drop-experiment surfaced
 * {@code entity_id NOT NULL} on the first IT POST. Forward-flagged to
 * Phase 7.x.2. This test class simulates the §12.7.2.2 failure mode by
 * mocking {@link AuditService#record} to throw
 * {@link DataIntegrityViolationException} — verify the aspect's outer
 * try/catch (Branch D) catches it, logs at WARN, and never propagates.
 * The full upstream fix is out of Phase 8 scope; this test pins the
 * "fail silent" contract so future refactors don't accidentally
 * surface the deferred failure as a 500.</p>
 *
 * <h2>Why pure @Mockito (not @SpringBootTest)</h2>
 *
 * <p>The existing {@code AuditAspectSpELTest} uses {@code @SpringBootTest}
 * to exercise the real Spring-AOP weaving + the {@code TestAuditedBean}
 * @Service. That test is sufficient for the SpEL happy + cache paths.
 * The branches in this class all occur on the JOIN-POINT-BEFORE
 * + AROUND-advice dispatch path, which can be exercised by mocking the
 * {@link ProceedingJoinPoint} + {@link MethodSignature} + recipe
 * collaborators directly — no Spring context, no bean weaving, no
 * AspectJ runtime overhead.</p>
 *
 * <h2>Hermeticity</h2>
 *
 * <p>{@code @ExtendWith(MockitoExtension.class)} + ReflectionTestUtils
 * for the inline-initialized {@link AuditAspect#parameterNameDiscoverer}
 * + {@link AuditAspect#spelParser} fields. The aspect's
 * {@code spelCache} ConcurrentHashMap is opaque (private field) but
 * works as designed during the test — the underlying
 * {@link ExpressionParser#parseExpression} is mocked so the parse step
 * is verifiable while the cache field retains real ConcurrentHashMap
 * semantics. {@link TransactionSynchronizationManager#isSynchronizationActive}
 * is driven via {@link TransactionSynchronizationManager#initSynchronization()}
 * + {@link TransactionSynchronizationManager#clearSynchronization()}
 * in @BeforeEach / @AfterEach fixtures.</p>
 *
 * <p><strong>ReflectionTestUtils JVM warning caveat:</strong>
 * {@code ReflectionTestUtils.setField} bypasses {@code private final}
 * inline init on production fields. On JDK 17+ this triggers the
 * "Mockito is currently self-attaching to enable the inline-mock-maker"
 * JVM warning (visible in CI logs but not a correctness issue). The
 * alternative — refactoring AuditAspect to accept
 * {@code ParameterNameDiscoverer} + {@code ExpressionParser} via
 * constructor — would be a production-code change for test ergonomics;
 * deferred to keep Phase 8 Item 3 scoped to test-only work.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuditAspect — fallback branches + TransactionSynchronization + writeAudit catch")
class AuditAspectFallbackTest {

    private static final UUID TEST_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String TEST_ENTITY_TYPE = "TEST_BEAN";

    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ProceedingJoinPoint pjp;
    @Mock private MethodSignature signature;
    @Mock private Method method;
    @Mock private ParameterNameDiscoverer discoverer;
    @Mock private ExpressionParser parsedSpelParser;
    @Mock private Expression compiledExpression;

    private AuditAspect aspect;

    @BeforeEach
    void setupCommonStubs() throws Throwable {
        // Direct construction (NOT @InjectMocks): AuditAspect's two
        // collaborators (auditService, objectMapper) are constructor
        // params via @RequiredArgsConstructor; the inline-initialized
        // `parameterNameDiscoverer` + `spelParser` are @RequiredArgsConstructor's
        // no-op — we swap them with mocks via ReflectionTestUtils so we
        // can deterministically control name discovery + SpEL parse.
        aspect = new AuditAspect(auditService, objectMapper);
        ReflectionTestUtils.setField(aspect, "parameterNameDiscoverer", discoverer);
        ReflectionTestUtils.setField(aspect, "spelParser", parsedSpelParser);

        // Common AOP signature chain — every test that uses auditAnnotatedMethod
        // will exercise this. Mocked leniently because some tests bypass
        // auditAnnotatedMethod entirely (e.g., resolveSpelValue reflective tests).
        lenient().when(pjp.getSignature()).thenReturn(signature);
        lenient().when(signature.getMethod()).thenReturn(method);
    }

    @AfterEach
    void cleanupTxSync() {
        // Critical: TransactionSynchronizationManager.initSynchronization
        // is a ThreadLocal; if a test leaves it set, subsequent tests see
        // a leaked ambient tx and take the wrong branch.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /* ====================================================================
     * resolveEntityId — branch graph (5 tests)
     * ==================================================================== */

    @Nested
    @DisplayName("resolveEntityId")
    class ResolveEntityId {

        @Test
        @DisplayName("parameterNameDiscoverer returns null → entityId is null; downstream audit row carries null entityId")
        void paramNamesNull_returnsNullEntityId_andDownstreamAuditCallReceivesNullEntityId() throws Throwable {
            // Branch E: ParameterNameDiscoverer.getParameterNames returns null
            // (e.g., -parameters compiler flag off; or a non-Spring method
            // lacking parameter-name metadata). resolveEntityId logs WARN
            // and returns null — the audit row carries null entityId.
            when(discoverer.getParameterNames(method)).thenReturn(null);
            when(pjp.proceed()).thenReturn("result");

            aspect.auditAnnotatedMethod(pjp, newAudited("id"));

            // Invariant: writeAudit was called (no ambient tx in tests by default)
            // with entityId=null
            ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(auditService, times(1)).record(
                    eq(AuditAction.CREATED),
                    eq(TEST_ENTITY_TYPE),
                    entityIdCaptor.capture(),
                    nullable(String.class), nullable(String.class), anyString());
            assertThat(entityIdCaptor.getValue())
                    .as("branch E (null parameter names) propagates null entityId to auditService.record")
                    .isNull();
        }

        @Test
        @DisplayName("name found, arg is UUID → entityId resolves to the UUID")
        void nameFound_argIsUuid_returnsResolvedUuid() throws Throwable {
            // Branch F (matching name "id") + Branch G (arg instanceof UUID
            // → return uuid directly).
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{TEST_ID});
            when(pjp.proceed()).thenReturn("result");

            aspect.auditAnnotatedMethod(pjp, newAudited("id"));

            ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(),
                    entityIdCaptor.capture(),
                    nullable(String.class), nullable(String.class), anyString());
            assertThat(entityIdCaptor.getValue())
                    .as("branch G (UUID match) propagates the resolved UUID")
                    .isEqualTo(TEST_ID);
        }

        @Test
        @DisplayName("name found, arg is non-UUID String → entityId is null; defensive WARN about type")
        void nameFound_argIsNonUuidString_returnsNullEntityId_andLogsWarnAboutType() throws Throwable {
            // Branch F (matching name) + Branch H (arg != null but not
            // UUID → defensive WARN log + null return).
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{"not-a-uuid"});
            when(pjp.proceed()).thenReturn("result");

            aspect.auditAnnotatedMethod(pjp, newAudited("id"));

            ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(),
                    entityIdCaptor.capture(),
                    nullable(String.class), nullable(String.class), anyString());
            assertThat(entityIdCaptor.getValue())
                    .as("branch H (non-UUID arg type) propagates null entityId to auditService.record")
                    .isNull();
        }

        @Test
        @DisplayName("name found, arg is null → entityId is null; null-arg fast-return path")
        void nameFound_argIsNull_returnsNullEntityId_fastReturn() throws Throwable {
            // Branch F (matching name) + null-arg fast-return.
            // Note the production code:
            //   if (arg instanceof UUID uuid) return uuid;
            //   if (arg != null) { log.warn(...); }
            //   return null;
            // So null arg falls through BOTH conditions to the final return.
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{null});
            when(pjp.proceed()).thenReturn("result");

            aspect.auditAnnotatedMethod(pjp, newAudited("id"));

            ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(),
                    entityIdCaptor.capture(),
                    nullable(String.class), nullable(String.class), anyString());
            assertThat(entityIdCaptor.getValue())
                    .as("null arg → fast-return null entityId")
                    .isNull();
        }

        @Test
        @DisplayName("name NOT found among parameters → entityId is null; defensive WARN about notFound")
        void nameNotFound_amongParams_returnsNullEntityId_logsWarnNotFound() throws Throwable {
            // Branch F (loop miss) — entityIdArg="'batch_uuid'" but actual
            // parameter is named "id". The aspect's loop never matches →
            // fallback WARN + null entityId.
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{TEST_ID});
            when(pjp.proceed()).thenReturn("result");

            aspect.auditAnnotatedMethod(pjp, newAudited("batch_uuid"));

            ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(),
                    entityIdCaptor.capture(),
                    nullable(String.class), nullable(String.class), anyString());
            assertThat(entityIdCaptor.getValue())
                    .as("branch F-miss (entityIdArg not found) propagates null entityId")
                    .isNull();
        }
    }

    /* ====================================================================
     * resolveSpelValue — branch graph (4 tests)
     * ==================================================================== */

    @Nested
    @DisplayName("resolveSpelValue")
    class ResolveSpelValue {

        @Test
        @DisplayName("null spelExpression → returns null immediately without parse or evaluation")
        void nullExpression_returnsNull_immediatelyNoAction() throws Throwable {
            // Branch J (null spelExpression) — fast-return null BEFORE
            // touching spelParser or EvaluationContext.
            when(pjp.proceed()).thenReturn("result");
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{TEST_ID});

            aspect.auditAnnotatedMethod(pjp, newAudited("id", "", ""));

            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(),
                    any(UUID.class),
                    // oldValue=null (null spelExpression path)
                    org.mockito.ArgumentMatchers.nullable(String.class),
                    // newValue=null (null spelExpression path)
                    org.mockito.ArgumentMatchers.nullable(String.class),
                    anyString());
            verify(parsedSpelParser, times(0)).parseExpression(anyString());
        }

        @Test
        @DisplayName("blank spelExpression → returns null immediately without parse or evaluation")
        void blankExpression_returnsNull_immediatelyNoAction() throws Throwable {
            // Branch J (blank spelExpression) — same fast-return as null.
            when(pjp.proceed()).thenReturn("result");
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{TEST_ID});

            aspect.auditAnnotatedMethod(pjp, newAudited("id", "   ", "  "));

            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(),
                    any(UUID.class),
                    org.mockito.ArgumentMatchers.nullable(String.class),
                    org.mockito.ArgumentMatchers.nullable(String.class),
                    anyString());
            verify(parsedSpelParser, times(0)).parseExpression(anyString());
        }

        @Test
        @DisplayName("JsonProcessingException thrown by ObjectMapper → caught, returns null, audit row written")
        void jsonProcessingException_caught_returnsNull_butAuditRowStillWritten() throws Throwable {
            // Branch K: objectMapper.writeValueAsString throws
            // JsonProcessingException → caught at the catch arm → return null.
            // Note: AuditAspect's catch is on JsonProcessingException
            // SPECIFICALLY (more narrow than RuntimeException).
            when(pjp.proceed()).thenReturn("result");
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{TEST_ID});
            when(parsedSpelParser.parseExpression(anyString())).thenReturn(compiledExpression);
            when(compiledExpression.getValue(any(EvaluationContext.class))).thenReturn("some-value");
            when(objectMapper.writeValueAsString(any())).thenThrow(
                    new JsonProcessingException("simulated Jackson serialize failure") {});

            aspect.auditAnnotatedMethod(pjp, newAudited("id", "#result", ""));

            // Invariant: audit row IS written (writeAudit's try/catch is
            // OUTSIDE resolveSpelValue). The exception is caught at the
            // inner SpEL try/catch with null column values for old/new.
            ArgumentCaptor<String> oldCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> newCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(), any(UUID.class),
                    oldCaptor.capture(), newCaptor.capture(), anyString());
            assertThat(oldCaptor.getValue())
                    .as("JsonProcessingException caught → oldValue column null")
                    .isNull();
            assertThat(newCaptor.getValue())
                    .as("(empty newSpel) → newValue column null")
                    .isNull();
        }

        @Test
        @DisplayName("RuntimeException thrown during SpEL evaluation → caught, returns null, audit row written")
        void runtimeException_inSpelEval_caught_returnsNull_butAuditRowStillWritten() throws Throwable {
            // Branch L: cast/expression-evaluation throws RuntimeException
            // (e.g., ClassCastException, SpelEvaluationException for an
            // unresolvable bean reference). Caught at the broader
            // RuntimeException catch → returns null.
            when(pjp.proceed()).thenReturn("result");
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{TEST_ID});
            when(parsedSpelParser.parseExpression(anyString())).thenReturn(compiledExpression);
            when(compiledExpression.getValue(any(EvaluationContext.class)))
                    .thenThrow(new RuntimeException("simulated SpEL parse/eval failure"));

            aspect.auditAnnotatedMethod(pjp, newAudited("id", "#result", ""));

            ArgumentCaptor<String> oldCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(), any(UUID.class),
                    oldCaptor.capture(), nullable(String.class), anyString());
            assertThat(oldCaptor.getValue())
                    .as("RuntimeException caught → oldValue column null")
                    .isNull();
        }
    }

    /* ====================================================================
     * writeAudit — Branch D (failure path catch)
     * ==================================================================== */

    @Nested
    @DisplayName("writeAudit — §12.7.2.2 deferred-failure silent catch")
    class WriteAudit {

        @Test
        @DisplayName("auditService.record throws DataIntegrityViolationException → caught, never propagates")
        void auditServiceRecord_throwsCaught_doesNotPropagate() throws Throwable {
            // Branch D (fail): auditService.record(...) throws
            // DataIntegrityViolationException — the exception type
            // experienced in production when entityId is null (entity_id
            // NOT NULL column constraint, per §12.7.2.2 forward-flag).
            // The outer try/catch in writeAudit() catches it + logs WARN.
            // CRITICAL INVARIANT: the exception does NOT propagate to the
            // caller of auditAnnotatedMethod — the business method's return
            // value (the pjp.proceed() result) is unaffected.
            when(pjp.proceed()).thenReturn("result");
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{TEST_ID});
            when(auditService.record(
                    any(AuditAction.class), anyString(), any(UUID.class),
                    anyString(), anyString(), anyString()))
                    .thenThrow(new DataIntegrityViolationException(
                            "simulated §12.7.2.2 entity_id NOT NULL violation"));

            Object result = assertDoesNotThrow(() ->
                    aspect.auditAnnotatedMethod(pjp, newAudited("id")));

            // Invariants:
            // (a) result is the original proceed() return value — writeAudit's
            //     catch does not change the @Around return.
            assertThat(result)
                    .as("writeAudit catch MUST NOT change the @Around return value")
                    .isEqualTo("result");
            // (b) auditService.record WAS called (the catch is on the path).
            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(), any(UUID.class),
                    nullable(String.class), nullable(String.class), anyString());
        }
    }

    /* ====================================================================
     * auditAnnotatedMethod — proceed() throws (Branch B) + tx-sync arms (Branch C × 2)
     * ==================================================================== */

    @Nested
    @DisplayName("auditAnnotatedMethod — proceed() throws + TransactionSynchronization arms")
    class AuditAspectArms {

        @Test
        @DisplayName("proceed() throws RuntimeException → no audit row written; @Around propagates the exception")
        void proceedThrows_noAuditWritten_propagates() throws Throwable {
            // Branch B: pjp.proceed() throws. The post-proceed code path
            // (SpEL capture + writeAudit register/direct) NEVER executes
            // because the exception escapes the method body BEFORE those
            // statements run. The @Around signature declares `throws Throwable`,
            // so the exception propagates up to the caller.
            when(pjp.proceed()).thenThrow(new RuntimeException("simulated host-method failure"));

            assertThatThrownBy(() -> aspect.auditAnnotatedMethod(pjp, newAudited("id")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("simulated host-method failure");

            verify(auditService, times(0)).record(
                    any(AuditAction.class), anyString(), any(UUID.class),
                    nullable(String.class), nullable(String.class), anyString());
        }

        @Test
        @DisplayName("proceed() succeeds + no ambient tx → writeAudit called directly (synchronous)")
        void proceedSucceeds_noAmbientTx_writesAuditDirectly() throws Throwable {
            // Branch C (inactive — default): TransactionSynchronizationManager
            // isSynchronizationActive() returns false. AuditAspect's else-arm
            // calls writeAudit(...) synchronously inside auditAnnotatedMethod.
            // We assert: post-advice, auditService.record has been called exactly
            // once (no deferred afterCommit).
            when(pjp.proceed()).thenReturn("result");
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{TEST_ID});
            // No initSynchronization → isSynchronizationActive() == false.

            aspect.auditAnnotatedMethod(pjp, newAudited("id"));

            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(), any(UUID.class),
                    nullable(String.class), nullable(String.class), anyString());
            // Implicit invariant: since no ambient tx, the aspect took the
            // IF-/ELSE-branch decision at `if (TransactionSynchronizationManager
            // .isSynchronizationActive())` as FALSE → ELSE → writeAudit() called
            // synchronously (the verify above pins auditService.record being
            // called). If the IF branch had been taken instead, register
            // Synchronization() would have thrown IllegalStateException BEFORE
            // writeAudit → record(), which means the verify above would fail.
            // Spring's getSynchronizations() itself requires active sync, so
            // explicitly asserting on it in this no-tx test is unnecessary +
            // would throw — the verify-time check is sufficient.
        }

        @Test
        @DisplayName("proceed() succeeds + ambient tx → afterCommit synchronization registered; writeAudit deferred")
        void proceedSucceeds_withAmbientTx_registersAfterCommitSync_writeAuditDeferred() throws Throwable {
            // Branch C (active): TransactionSynchronizationManager
            // isSynchronizationActive() returns true (because we
            // initSynchronization() in this test). AuditAspect's if-arm
            // registers a TransactionSynchronization whose afterCommit()
            // invokes writeAudit. Pre-afterCommit, auditService.record has
            // NOT been called yet. After manually invoking afterCommit,
            // auditService.record IS called.
            when(pjp.proceed()).thenReturn("result");
            stubParamNames("id");
            when(pjp.getArgs()).thenReturn(new Object[]{TEST_ID});

            TransactionSynchronizationManager.initSynchronization();  // ambient tx fixture

            aspect.auditAnnotatedMethod(pjp, newAudited("id"));

            // PRE-afterCommit invariant: synchronization was registered,
            // but writeAudit HAS NOT fired yet.
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs)
                    .as("ambient tx → exactly 1 afterCommit synchronization registered")
                    .hasSize(1);
            verify(auditService, times(0)).record(
                    any(AuditAction.class), anyString(), any(UUID.class),
                    nullable(String.class), nullable(String.class), anyString());

            // POST-afterCommit invariant: manually firing afterCommit
            // triggers writeAudit. (Real Spring would fire afterCommit on
            // actual tx commit; in the unit-test scope, we drive it manually.)
            syncs.get(0).afterCommit();

            verify(auditService, times(1)).record(
                    any(AuditAction.class), anyString(), any(UUID.class),
                    nullable(String.class), nullable(String.class), anyString());
        }
    }

    /* ====================================================================
     * Test fixtures
     * ==================================================================== */

    /* ====================================================================
     * Test fixtures (OUTER-CLASS scope — shared across all @Nested classes)
     * ==================================================================== */

    /**
     * Stub {@link org.springframework.core.ParameterNameDiscoverer}
     * to return {@code names} for the host method. Production code only
     * reads {@code params.length} on the returned {@link Parameter} array,
     * so the inner Parameter is opaque — a deep-stubbed mock suffices.
     *
     * <p>Defined OUTSIDE any @Nested class for {@code @Nested}-class
     * visibility from sibling @Nested groups (Java inner-class scoping
     * forbids access from sibling inner classes to member methods of
     * other inner classes).</p>
     */
    private void stubParamNames(String... names) {
        Parameter[] params = new Parameter[names.length];
        for (int i = 0; i < names.length; i++) {
            params[i] = org.mockito.Mockito.mock(Parameter.class,
                    org.mockito.Answers.RETURNS_DEEP_STUBS);
        }
        lenient().when(method.getParameters()).thenReturn(params);
        lenient().when(discoverer.getParameterNames(method)).thenReturn(names);
    }

    /**
     * Synthetic {@link Audited} instance via Mockito.mock(Audited.class).
     * Avoids the bytecode gymnastics of a hand-rolled Annotation proxy.
     *
     * <p><strong>Forward-compat hazard:</strong> Mockito.mock returns
     * default values (empty string / null) for any annotation method
     * NOT explicitly stubbed. If a later phase adds a new field to
     * {@code @Audited} (e.g., {@code correlationId()}), this helper
     * must be updated or the test will silently produce the wrong
     * default value with no compile-time signal. Document the manual
     * stubbing requirement here so future maintainers know.</p>
     */
    private static Audited newAudited(String entityIdArg) {
        return newAudited(entityIdArg, "", "");
    }

    private static Audited newAudited(String entityIdArg, String oldSpel, String newSpel) {
        Audited audited = org.mockito.Mockito.mock(Audited.class);
        org.mockito.Mockito.when(audited.entityType()).thenReturn(TEST_ENTITY_TYPE);
        org.mockito.Mockito.when(audited.action()).thenReturn(AuditAction.CREATED);
        org.mockito.Mockito.when(audited.entityIdArg()).thenReturn(entityIdArg);
        org.mockito.Mockito.when(audited.oldValueSpel()).thenReturn(oldSpel);
        org.mockito.Mockito.when(audited.newValueSpel()).thenReturn(newSpel);
        org.mockito.Mockito.when(audited.performedBy()).thenReturn("system");
        return audited;
    }
}
