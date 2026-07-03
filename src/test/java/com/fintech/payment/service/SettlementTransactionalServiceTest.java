package com.fintech.payment.service;

import com.fintech.payment.model.entity.Payment;
import com.fintech.payment.model.entity.SettlementBatch;
import com.fintech.payment.model.enums.PaymentStatus;
import com.fintech.payment.model.enums.SettlementStatus;
import com.fintech.payment.repository.PaymentRepository;
import com.fintech.payment.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8 §12.6.3.1 — coverage-push unit tests for
 * {@link SettlementTransactionalService}.
 *
 * <p>Closes the Phase 6 §12.6.3 forward-flag + §12.5.2 forward-flag:
 * the claim-loop body and the {@link #markBatchFailed} fallback path
 * had <strong>0% branch coverage</strong> because the only test path
 * that touched them was {@code IdempotencyCacheMissTest}'s
 * end-to-end IT (skipping the per-class invariants the @Transactional
 * + @Retryable wiring depends on).</p>
 *
 * <h2>What this test exercises</h2>
 *
 * <ol>
 *   <li><strong>Missing-batch branch.</strong> {@code findById} returning
 *       empty triggers {@code orElseThrow} — the {@code IllegalStateException}
 *       path. Critical: a worker invocation against a stale UUID must NOT
 *       silently no-op; the exception bubbles to {@code @Retryable} and
 *       eventually to {@code @Recover} → {@code markBatchFailed}.</li>
 *   <li><strong>Already-SETTLED branch.</strong> {@code batch.getStatus() ==
 *       SETTLED} short-circuits the entire claim loop. This is the
 *       idempotency guarantee for {@code POST /api/v1/settlements/{id}/process}
 *       re-fired on a terminal-state batch.</li>
 *   <li><strong>Happy claim loop.</strong> 3 COMPLETED payments → batch
 *       flips {@code OPEN → PROCESSING → SETTLED} with the right totals
 *       + a single {@code Instant.now(clock)} stamp shared across the
 *       batch row AND every payment row (ensures reconciliation reports
 *       can group-by processedAt).</li>
 *   <li><strong>Empty claim window.</strong> Zero COMPLETED payments →
 *       batch still flips OPEN → SETTLED with totals=0 / amount=0.
 *       Realistic on the first day of a fresh system before any traffic.</li>
 *   <li><strong>Mid-loop OLF propagation.</strong> The third-party race
 *       where a concurrent user-API {@code save} bumps a {@code Payment.@Version}
 *       mid-loop — the failing {@code save} must propagate the OLF so
 *       {@code SettlementWorker.processBatchAsync}'s {@code @Retryable}
 *       sees it and runs {@code @Recover}. NOT silently swallowed.</li>
 *   <li><strong>{@code markBatchFailed} present batch.</strong>
 *       After retry exhaust, the {@code @Recover} path tags the batch
 *       FAILED, stamps processedAt, persists. The save-mutation ordering
 *       is verified (set-then-save).</li>
 *   <li><strong>{@code markBatchFailed} missing batch.</strong>
 *       Idempotent silent no-op — a missing batch does NOT raise
 *       (this is a post-retry housekeeping call).</li>
 *   <li><strong>Class-level {@code @Transactional(REQUIRES_NEW)}.</strong>
 *       Reflection-verified. The REQUIRES_NEW propagation guarantees
 *       FRESH tx on every {@code @Retryable} attempt → cross-attempt
 *       state isolation. Without REQUIRES_NEW, the second retry attempt
 *       would inherit the first's rolled-back state and re-bump the
 *       version indefinitely.</li>
 * </ol>
 *
 * <h2>Why pure @Mockito (not @SpringBootTest)</h2>
 *
 * <p>The class-level {@code @Transactional} is an AOP concern only
 * active under Spring's proxy machinery; we verify that invariant at
 * the metadata level (test 8) so the test itself runs without Spring
 * context. The {@code @Audited} annotation on
 * {@link SettlementTransactionalService#processBatchTransactional} is
 * a separate aspect-driver concern — already covered by IT-level tests
 * (Phase 7 §12.7.1 Batch 5). This unit test focuses on the BRANCH
 * graph of the body method, not the cross-cutting wiring.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementTransactionalService — claim-loop + retry-recovered invariants")
class SettlementTransactionalServiceTest {

    private static final UUID BATCH_ID = UUID.fromString("0d4f1e98-1a3b-4c5d-9e6f-7a8b9c0d1e2f");
    private static final Instant FIXED_STAMP = Instant.parse("2026-07-03T12:00:00Z");

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private SettlementTransactionalService service;

    @BeforeEach
    void freezeClock() {
        // Strict-stubbing tolerated: some test paths don't call Instant.now()
        // so we mark the stub lenient to avoid UnnecessaryStubbingException.
        lenient().when(clock.instant()).thenReturn(FIXED_STAMP);
    }

    /* ====================================================================
     * processBatchTransactional — branch graph
     * ==================================================================== */

    @Nested
    @DisplayName("processBatchTransactional")
    class ProcessBatchTransactional {

        @Test
        @DisplayName("missing batch → IllegalStateException (lets @Retryable see it)")
        void missingBatch_throwsIllegalStateException_andDoesNotClaimNorFinalize() {
            // Branch A: findById returns empty → orElseThrow fires.
            when(settlementRepository.findById(BATCH_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.processBatchTransactional(BATCH_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BATCH_ID.toString())
                    .hasMessageContaining("missing mid-processing");

            // Invariants: no claim, no finalize, no save. The exception
            // is the ONLY side-effect, so @Retryable sees a clean, fresh
            // retry boundary without half-written intermediate rows.
            verify(settlementRepository, never()).save(any(SettlementBatch.class));
            verify(paymentRepository, never()).findByStatusAndSettlementBatchIdIsNull(any());
            verify(paymentRepository, never()).save(any(Payment.class));
        }

        @Test
        @DisplayName("already-SETTLED batch → early-return log, no claim, no re-finalize")
        void settledBatch_earlyReturns_andDoesNotReclaimOrFinalize() {
            // Branch B: batch.getStatus() == SETTLED → return.
            SettlementBatch settled = newBatch(SettlementStatus.SETTLED, "USD");
            when(settlementRepository.findById(BATCH_ID)).thenReturn(Optional.of(settled));

            service.processBatchTransactional(BATCH_ID);

            // Invariants: the log message + early-return. No claim
            // window query, no save on either repo. A re-triggered
            // POST /api/v1/settlements/{id}/process on a SETTLED batch
            // is a no-op at this layer.
            verify(settlementRepository, never()).save(any(SettlementBatch.class));
            verify(paymentRepository, never()).findByStatusAndSettlementBatchIdIsNull(any());
            verify(paymentRepository, never()).save(any(Payment.class));
        }

        @Test
        @DisplayName("happy path: 3 COMPLETED payments → SETTLED with correct totals + shared stamp")
        void happyPath_openBatch_claimsCompletedPayments_finalizesSettled() {
            // Branch C: claim loop with non-empty list, all saves succeed.
            SettlementBatch batch = newBatch(SettlementStatus.OPEN, "USD");
            List<Payment> claimable = List.of(
                    newPayment(new BigDecimal("10.00"), 1L),
                    newPayment(new BigDecimal("20.50"), 1L),
                    newPayment(new BigDecimal("5.00"), 1L)
            );

            // Snapshot settlement statuses AT SAVE TIME — Mockito's captor
            // would otherwise capture the live `batch` reference twice,
            // and by the time the assertion runs, the object's status has
            // been mutated to SETTLED for BOTH captured references. The
            // doAnswer list records status snapshots per save call so we
            // can distinguish PROCESSING (save #1) from SETTLED (save #2).
            List<SettlementStatus> savedStatusesAtCallTime = new ArrayList<>();
            List<Integer> savedTotalsAtCallTime = new ArrayList<>();

            when(settlementRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
            when(paymentRepository.findByStatusAndSettlementBatchIdIsNull(PaymentStatus.COMPLETED))
                    .thenReturn(claimable);
            // Stub save(...) with an Answer that snapshots per-call state.
            // JpaRepository.save(S) returns S, so the answer returns the
            // input batch unchanged (matches production behavior in spirit).
            doAnswer(inv -> {
                SettlementBatch savedBatch = inv.getArgument(0);
                savedStatusesAtCallTime.add(savedBatch.getStatus());
                savedTotalsAtCallTime.add(savedBatch.getTotalPayments());
                return savedBatch;
            }).when(settlementRepository).save(any(SettlementBatch.class));

            service.processBatchTransactional(BATCH_ID);

            // Invariant 1: Save sequence — PROCESSING flip first, SETTLED
            // finalize LAST, with totals reset(0) → totals(3) snapshot.
            assertThat(savedStatusesAtCallTime)
                    .as("batch status sequence at each save call (PROCESSING → SETTLED)")
                    .containsExactly(SettlementStatus.PROCESSING, SettlementStatus.SETTLED);
            assertThat(savedTotalsAtCallTime)
                    .as("batch totalPayments sequence at each save call (zero → claim-loop total)")
                    .containsExactly(0, 3);

            // The live `batch` reference now has its final state — assert
            // the post-loop SETTLED outcome directly on it (captures the
            // FR-3.3 contract end-state).
            assertThat(batch.getStatus()).isEqualTo(SettlementStatus.SETTLED);
            assertThat(batch.getTotalPayments()).isEqualTo(3);
            assertThat(batch.getTotalAmount()).isEqualByComparingTo(new BigDecimal("35.50"));
            assertThat(batch.getProcessedAt()).isEqualTo(FIXED_STAMP);
            assertThat(batch.getVersion())
                    .as("service code does NOT mutate @Version (Hibernate bumps at flush time)")
                    .isEqualTo(0L);
            verify(settlementRepository, times(2)).save(any(SettlementBatch.class));

            // Invariant 2: each claimed Payment row saved exactly once, with
            // BATCH_ID stamped + processedAt = same FIXED_STAMP (single
            // Instant.now(clock) call shared across the loop). This is the
            // FR-3.3 single-tx guarantee — all 3 payment writes + the batch
            // SETTLED write are within the same REQUIRES_NEW tx boundary.
            // Payment objects are not mutated after save(...) (no further
            // write-side field-set in the production code), so a plain
            // verify(times(3)).save(...) is sufficient.
            verify(paymentRepository, times(3)).save(any(Payment.class));
            assertThat(claimable).allSatisfy(p -> {
                assertThat(p.getSettlementBatchId())
                        .as("Payment stamped with the batch UUID (claim-loop invariant)")
                        .isEqualTo(BATCH_ID);
                assertThat(p.getProcessedAt())
                        .as("Payment processedAt = same Instant.now(clock) call as batch row (single-tx invariant)")
                        .isEqualTo(FIXED_STAMP);
            });
        }

        @Test
        @DisplayName("empty claim window → SETTLED with zero totals (no payment saves fired)")
        void emptyClaimWindow_finalizesSettled_withZeroTotals() {
            // Branch C with empty list: loop is skipped but finalize still runs.
            // Same live-reference problem as the happy-path test: snapshot
            // statuses at save time via doAnswer.
            SettlementBatch batch = newBatch(SettlementStatus.OPEN, "USD");
            List<SettlementStatus> savedStatusesAtCallTime = new ArrayList<>();

            when(settlementRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
            when(paymentRepository.findByStatusAndSettlementBatchIdIsNull(PaymentStatus.COMPLETED))
                    .thenReturn(List.of());
            doAnswer(inv -> {
                SettlementBatch savedBatch = inv.getArgument(0);
                savedStatusesAtCallTime.add(savedBatch.getStatus());
                return savedBatch;
            }).when(settlementRepository).save(any(SettlementBatch.class));

            service.processBatchTransactional(BATCH_ID);

            assertThat(savedStatusesAtCallTime)
                    .as("batch status sequence at each save call (PROCESSING → SETTLED) — even with empty claim window")
                    .containsExactly(SettlementStatus.PROCESSING, SettlementStatus.SETTLED);

            // Post-loop SETTLED outcome on the live batch reference.
            assertThat(batch.getStatus()).isEqualTo(SettlementStatus.SETTLED);
            assertThat(batch.getTotalPayments()).isZero();
            assertThat(batch.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(batch.getProcessedAt()).isEqualTo(FIXED_STAMP);
            verify(settlementRepository, times(2)).save(any(SettlementBatch.class));
            verify(paymentRepository, never()).save(any(Payment.class));
        }

        @Test
        @DisplayName("mid-loop OptimisticLockingFailureException propagates (lets @Retryable see it)")
        void midLoopOptimisticLockingFailureException_propagates_andDoesNotFinalize() {
            // Branch C with mid-loop OLF: 2 of 3 payment saves succeed; the
            // 2nd attempted save throws OLF → the entire claim loop
            // UNRAVELS without finalizing the batch. The OLF crosses the
            // method boundary so SettlementWorker.processBatchAsync's
            // @Retryable interceptor catches it and runs @Recover.
            SettlementBatch batch = newBatch(SettlementStatus.OPEN, "USD");
            Payment p0 = newPayment(new BigDecimal("10.00"), 1L);
            Payment p1 = newPayment(new BigDecimal("20.00"), 1L);
            Payment p2 = newPayment(new BigDecimal("30.00"), 1L);
            List<Payment> claimable = List.of(p0, p1, p2);

            when(settlementRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
            when(paymentRepository.findByStatusAndSettlementBatchIdIsNull(PaymentStatus.COMPLETED))
                    .thenReturn(claimable);
            // paymentRepository.save() returns Payment (non-void, per
            // JpaRepository.save signature), so stubs use when().thenX,
            // NOT doNothing(). Stub p0's save to return p0 (default is null);
            // a real JpaRepository would return the merged entity. Stub
            // p1's save to throw OLF on the version conflict.
            when(paymentRepository.save(p0)).thenReturn(p0);
            when(paymentRepository.save(p1))
                    .thenThrow(new OptimisticLockingFailureException("concurrent @Version bump on p1"));
            // p2 is intentionally NOT stubbed — the loop never reaches it,
            // so Mockito will never invoke save(p2). If the test ever
            // surfaces Mockito default-stubbing complaints, that's the
            // signal the loop-boundary broke.

            assertThatThrownBy(() -> service.processBatchTransactional(BATCH_ID))
                    .isInstanceOf(OptimisticLockingFailureException.class)
                    .hasMessageContaining("concurrent @Version bump on p1");

            // Invariants on the rolled-back path:
            // (a) Batch saved ONCE (PROCESSING flip) — final SETTLED save
            //     NEVER fires because the OLF crossed the method boundary
            //     before reaching the SETTLED finalize code.
            // (b) Payment saves: p0 succeeded, p1 attempted-and-threw,
            //     p2 NOT attempted (loop broke on the OLF before
            //     reaching the 3rd item). Explicit never().save(p2)
            //     tightens the implication of times(2): if a refactor
            //     adds pre- or post-loop work that bypasses OLF, this
            //     assertion fails first.
            ArgumentCaptor<SettlementBatch> batchCaptor = ArgumentCaptor.forClass(SettlementBatch.class);
            verify(settlementRepository, times(1)).save(batchCaptor.capture());
            SettlementBatch onlyBatchSave = batchCaptor.getValue();
            assertThat(onlyBatchSave.getStatus())
                    .as("only the PROCESSING pre-claim save fired — SETTLED save did NOT run after OLF")
                    .isEqualTo(SettlementStatus.PROCESSING);

            verify(paymentRepository, times(2)).save(any(Payment.class));
            verify(paymentRepository).save(p0);
            verify(paymentRepository).save(p1);
            verify(paymentRepository, never()).save(p2);
        }
    }

    /* ====================================================================
     * markBatchFailed — branch graph
     * ==================================================================== */

    @Nested
    @DisplayName("markBatchFailed")
    class MarkBatchFailed {

        @Test
        @DisplayName("present batch → set FAILED, stamp processedAt, persist")
        void presentBatch_setFailedAndSave_withStampedProcessedAt() {
            // Branch D present: the lambda inside ifPresent runs.
            SettlementBatch processing = newBatch(SettlementStatus.PROCESSING, "USD");
            when(settlementRepository.findById(BATCH_ID)).thenReturn(Optional.of(processing));

            service.markBatchFailed(BATCH_ID, "retries exhausted: OptimisticLockingFailureException");

            ArgumentCaptor<SettlementBatch> captor = ArgumentCaptor.forClass(SettlementBatch.class);
            verify(settlementRepository).save(captor.capture());

            SettlementBatch failedSave = captor.getValue();
            assertThat(failedSave.getStatus())
                    .as("batch tagged FAILED — terminal failure state")
                    .isEqualTo(SettlementStatus.FAILED);
            assertThat(failedSave.getProcessedAt())
                    .as("processedAt stamped with FIXED_STAMP — reconciliation reports pin this minute")
                    .isEqualTo(FIXED_STAMP);
            // Reason text only appears in the log message; not stored on
            // the row (reason is in the audit log via @Audited/BATCH_FAILED).
            assertThat(failedSave.getStatus()).isEqualTo(SettlementStatus.FAILED);
        }

        @Test
        @DisplayName("missing batch → silent no-op (idempotent against post-retry housekeeping)")
        void missingBatch_isSilentNoOp_andDoesNotSave() {
            // Branch D absent: ifPresent lambda NOT invoked → empty batch
            // never causes a save. The @Recover @Post-retry path is
            // idempotent against a batch that was concurrently deleted.
            when(settlementRepository.findById(BATCH_ID)).thenReturn(Optional.empty());

            service.markBatchFailed(BATCH_ID, "retries exhausted: OptimisticLockingFailureException");

            verify(settlementRepository, never()).save(any(SettlementBatch.class));
        }
    }

    /* ====================================================================
     * Retry-recovered tx invariants — class-level metadata verification
     * ==================================================================== */

    @Nested
    @DisplayName("class-level retry-recovered transactional invariants")
    class ClassInvariants {

        @Test
        @DisplayName("@Transactional(propagation = REQUIRES_NEW) — fresh tx per @Retryable attempt")
        void transactionalAnnotationIsREQUIRES_NEW_atClassLevel() {
            // The @Retryable boundary on SettlementWorker.processBatchAsync
            // re-invokes processBatchTransactional on each retry attempt.
            // REQUIRES_NEW isolates each attempt's tx — without it, rolled-back
            // state from attempt N would propagate to attempt N+1, defeating
            // the retry semantics.
            Transactional tx = SettlementTransactionalService.class
                    .getAnnotation(Transactional.class);

            assertThat(tx)
                    .as("class-level @Transactional must be present for proxy activation")
                    .isNotNull();
            assertThat(tx.propagation())
                    .as("REQUIRES_NEW guarantees a fresh tx on each @Retryable attempt — "
                            + "without it, rolled-back state would leak across retries")
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }

        @Test
        @DisplayName("processBatchTransactional itself has no method-level @Transactional override")
        void processBatchTransactional_usesClassLevelTransactional() {
            // Sanity: confirm the method-level @Transactional is NOT present
            // (would override the class-level REQUIRES_NEW). The class-level
            // annotation inherits to all non-overriding methods.
            // assertDoesNotThrow (JUnit 5) wraps the checked
            // NoSuchMethodException into a test-framework diagnostic if the
            // reflection call ever fails — cleaner than the try/catch +
            // AssertionError pattern and one line shorter.
            Transactional methodTx = assertDoesNotThrow(() ->
                    SettlementTransactionalService.class
                            .getDeclaredMethod("processBatchTransactional", UUID.class)
                            .getAnnotation(Transactional.class));
            assertThat(methodTx)
                    .as("no method-level @Transactional override — class-level REQUIRES_NEW applies")
                    .isNull();
        }
    }

    /* ====================================================================
     * Test fixtures
     * ==================================================================== */

    private static SettlementBatch newBatch(SettlementStatus status, String currency) {
        SettlementBatch batch = new SettlementBatch();
        batch.setId(BATCH_ID);
        batch.setBatchDate(java.time.LocalDate.parse("2026-07-03"));
        batch.setStatus(status);
        batch.setTotalPayments(0);
        batch.setTotalAmount(BigDecimal.ZERO);
        batch.setCurrency(currency);
        batch.setVersion(0L);
        batch.setCreatedAt(FIXED_STAMP);
        batch.setUpdatedAt(FIXED_STAMP);
        return batch;
    }

    private static Payment newPayment(BigDecimal amount, long version) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setIdempotencyKey("IDEM-" + UUID.randomUUID());
        p.setSourceAccountId(UUID.randomUUID());
        p.setTargetAccountId(UUID.randomUUID());
        p.setAmount(amount);
        p.setCurrency("USD");
        p.setStatus(PaymentStatus.COMPLETED);
        p.setVersion(version);
        p.setCreatedAt(FIXED_STAMP);
        p.setUpdatedAt(FIXED_STAMP);
        return p;
    }
}
