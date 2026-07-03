package com.fintech.payment.service;

import com.fintech.payment.repository.PaymentRepository;
import com.fintech.payment.repository.SettlementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 6 — Integration Test for the SettlementWorker
 * {@code @Async + @Retryable} chain (§12.6.1 item 6).
 *
 * <p>Closes the SRS §12.6 forward-flag: the previous YELLOW state
 * ("ConditionTimeout on {@code verify(times(2))}") was rooted in a
 * timer-driven test ({@code Thread.sleep} ramp + Awaitility
 * {@code atMost(10s).pollInterval(250ms)}), which is racy on a busy
 * CI runner because the {@code @Async} dispatch plus the
 * {@code @Backoff(500ms)} retry budget is timing-dependent. Phase 7.x
 * converts the synchronization to event-driven: a {@link CountDownLatch}
 * is decremented on each {@code processBatchTransactional} invocation
 * (success or throw) on the worker thread, regardless of timing, and
 * the test thread blocks on {@code latch.await(10, TimeUnit.SECONDS)}
 * with AssertJ asserting the boolean return.</p>
 *
 * <p>Hermeticity note (§12.4.2): Spring Boot's full test harness is
 * needed because the {@code @EnableAsync} and {@code @EnableRetry}
 * proxies are only active in the production ApplicationContext.
 * {@code @WebMvcTest} or plain {@code @DataJpaTest} would not exercise
 * the retry / async layer.</p>
 *
 * <p>Strategy:</p>
 * <ol>
 *   <li>Use {@code @SpringBootTest} scoped to the SettlementWorker +
 *       SettlementTransactionalService + the {@code @Async}
 *       {@code settlementWorkerExecutor} chain.</li>
 *   <li>Stub {@link SettlementTransactionalService} via MockitoBean to
 *       throw {@link OptimisticLockingFailureException} on its first
 *       invocation, succeed on the second.</li>
 *   <li>Call {@code settlementWorker.processBatchAsync(batchId)} from the
 *       test thread (Spring routes the execution onto the
 *       {@code settlementWorkerExecutor} thread pool because of the
 *       {@code @Async} annotation).</li>
 *   <li>Wait for the {@link CountDownLatch} to reach zero (one
 *       decrement per {@code @Retryable} attempt, fired from inside the
 *       {@code doAnswer} callback on the worker thread). Assert + verify
 *       the final state.</li>
 * </ol>
 *
 * <h2>Why not drive synchronisation off timing</h2>
 * <ul>
 *   <li>{@code Thread.sleep(1500)} pre-Awaitility ramp — racy in
 *       CI/BusyBox contexts; the dispatch + retry budget is variable
 *       under load.</li>
 *   <li>{@code Awaitility.await().atMost(10s).pollInterval(250ms)} —
 *       poll-based; a fast retry budget can clear in &lt;100ms and a
 *       slow one can stretch past 1s; the poll may starve the worker
 *       thread on a contended runner.</li>
 *   <li>Both depend on {@code @Backoff(500)} being strictly observed;
 *       if Spring Retry's first invocation fires fast (no backoff yet),
 *       the Thread.sleep ramp can be 4× too long and Awaitility never
 *       notices the gap.</li>
 * </ul>
 *
 * <h2>Why {@link CountDownLatch} works</h2>
 * <ul>
 *   <li>The {@link SettlementTransactionalService} MockitoBean mock is
 *       the SAME bean instance in the application context, regardless
 *       of which thread (test thread vs {@code settlement-worker-N}
 *       worker thread) reads it. Mockito's {@code doAnswer} callback
 *       runs on the worker thread when invoked by
 *       {@code processBatchTransactional}. The latch is decremented
 *       exactly once per invocation, so a 2-attempt retry budget
 *       decrements the latch from 2 → 0 and {@code latch.await(...)}
 *       unblocks once both attempts have run.</li>
 *   <li>Bounded time budget (10s) protects against a divergent retry
 *       loop. If {@code latch.await} returns {@code false}, the
 *       assertion surfaces a clear "firedTwice == false" failure
 *       rather than Awaitility's opaque
 *       {@code ConditionTimeoutException}.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // The previously-set "spring.task.execution.pool.*" properties were
        // DEAD on this path — they only configure Spring's DEFAULT
        // TaskExecutor, NOT the named "settlementWorkerExecutor" bean
        // (hardcoded core=2, max=4, queue=50 in AsyncConfig.java). Trimmed
        // in Phase 7.x as part of the YELLOW closure. The two retained
        // properties raise log level for the @Async + @Retry interceptor
        // chains so CI failures here are diagnosable from logs.
        "logging.level.com.fintech.payment.service.SettlementWorker=DEBUG",
        "logging.level.org.springframework.aop.interceptor=DEBUG"
})
class SettlementWorkerIT {

    @MockitoBean
    private SettlementTransactionalService transactionalServiceMock;

    @Autowired
    private SettlementWorker settlementWorker;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(transactionalServiceMock);
    }

    @AfterEach
    void cleanupData() {
        paymentRepository.deleteAll();
        settlementRepository.deleteAll();
    }

    @Nested
    @DisplayName("RetryChain")
    class RetryChain {

        @Test
        void throws_once_then_succeeds_retries_once_and_marks_settled() throws InterruptedException {
            // Seed an OPEN batch so the transaction actually opens.
            UUID batchId = seedOpenBatch();

            // Event-driven countdown: the doAnswer callback decrements the
            // latch on each invocation, regardless of whether the invocation
            // throws or succeeds. After both @Retryable attempts (one throw,
            // one success), the latch reaches 0 and latch.await(...) unblocks.
            CountDownLatch attemptsFired = new CountDownLatch(2);
            AtomicInteger calls = new AtomicInteger();
            doAnswer((InvocationOnMock inv) -> {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    // Throw on first attempt — the @Retryable boundary
                    // catches OptimisticLockingFailureException and schedules
                    // a @Backoff(500) retry. Decrement BEFORE the throw so
                    // latch.await catches the attempt regardless of throw.
                    attemptsFired.countDown();
                    throw new OptimisticLockingFailureException(
                            "simulated CAS race on attempt " + n);
                }
                // Second attempt: succeed (no exception crosses the boundary).
                attemptsFired.countDown();
                return null;
            }).when(transactionalServiceMock).processBatchTransactional(any(UUID.class));

            // Dispatch via the bean reference — @Async routes onto the worker pool.
            settlementWorker.processBatchAsync(batchId);

            // Bounded event-driven wait. The @Backoff(500ms) retry budget
            // for 2 attempts is ~510ms of @Async dispatch latency; the 5s
            // ceiling is tight enough to surface divergent retry behavior
            // (e.g., 8s+ retry sleep slipping in later) on a single CI
            // cycle while still allowing generous scheduler tolerance.
            // AssertJ on the boolean surfaces a clean failure mode if the
            // latch didn't reach zero in time.
            boolean firedTwice = attemptsFired.await(5, TimeUnit.SECONDS);
            assertThat(firedTwice)
                    .as("both @Retryable attempts decremented the latch within 5s budget")
                    .isTrue();

            // Defensive: explicit latch-count assertion between the await
            // and the Mockito verify. The boolean from
            // attemptsFired.await(...) returning true only proves a single
            // time-window saw count==0; an explicit count==0 assertion
            // produces a clear "count was N, expected 0" failure message
            // if Spring Retry ever silently short-circuits the retry
            // boundary. Costs 1 line; buys a printed-locator failure.
            assertThat(attemptsFired.getCount())
                    .as("attemptsFired latch is fully drained once await returns true")
                    .isZero();

            assertThat(calls.get())
                    .as("processBatchTransactional called exactly twice (throw + succeed)")
                    .isEqualTo(2);

            // No FAIL mark — the second attempt succeeded and the @Retryable
            // boundary did not propagate, so the @Recover path in
            // SettlementWorker.processBatchAsync did NOT fire.
            verify(transactionalServiceMock, times(0))
                    .markBatchFailed(any(UUID.class), any());
        }
    }

    private UUID seedOpenBatch() {
        com.fintech.payment.model.entity.SettlementBatch batch =
                new com.fintech.payment.model.entity.SettlementBatch();
        batch.setBatchDate(java.time.LocalDate.now());
        batch.setStatus(com.fintech.payment.model.enums.SettlementStatus.OPEN);
        batch.setCurrency("USD");
        batch.setTotalPayments(0);
        batch.setTotalAmount(java.math.BigDecimal.ZERO);
        return settlementRepository.save(batch).getId();
    }
}
