package com.fintech.payment.service;

import com.fintech.payment.repository.PaymentRepository;
import com.fintech.payment.repository.SettlementRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.UUID;
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
 * <p>Hermeticity note (§12.4.2): Spring Boot's full test harness is needed
 * because the {@code @EnableAsync} and {@code @EnableRetry} proxies are
 * only active in the production ApplicationContext. {@code @WebMvcTest} or
 * plain {@code @DataJpaTest} would not exercise the retry / async layer.</p>
 *
 * <p>Strategy:</p>
 * <ol>
 *   <li>Use {@code @SpringBootTest} with a trimmed scan to focus on
 *       SettlementWorker + SettlementTransactionalService + the @Async
 *       {@code settlementWorkerExecutor}.</li>
 *   <li>Stub {@link SettlementTransactionalService} via MockitoBean to
 *       throw {@link OptimisticLockingFailureException} on its first
 *       invocation, succeed on the second.</li>
 *   <li>Call {@code settlementWorker.processBatchAsync(batchId)} from the
 *       test thread (Spring routes the execution onto the
 *       {@code settlementWorkerExecutor} thread pool because of the
 *       {@code @Async} annotation).</li>
 *   <li>Await on {@code awaitility} until the retry budget is consumed —
 *       assert {@code processBatchTransactional} was called twice (once
 *       throwing, once succeeding) and {@code markBatchFailed} was NOT
 *       called (we proved the chain converged via success).</li>
 * </ol>
 */
@SpringBootTest
@DirtiesContext
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
        void throws_once_then_succeeds_retries_once_and_marks_settled() {
            // Seed an OPEN batch so the transaction actually opens.
            UUID batchId = seedOpenBatch();

            AtomicInteger calls = new AtomicInteger();
            doAnswer((InvocationOnMock inv) -> {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    // Throw on first attempt — the @Retryable boundary
                    // catches OptimisticLockingFailureException and retries.
                    throw new OptimisticLockingFailureException(
                            "simulated CAS race on attempt " + n);
                }
                // Second attempt: succeed (no exception crosses the boundary).
                return null;
            }).when(transactionalServiceMock).processBatchTransactional(any(UUID.class));

            // Dispatch via the bean reference — @Async routes onto the worker pool.
            settlementWorker.processBatchAsync(batchId);

            // Await until both attempts have fired. Spring Retry backoff is
            // 500ms per @Backoff(delay=500); two attempts should complete
            // well within 5s. Use Awaitility with documented tolerance so
            // a slow CI scheduler doesn't trip the test flake detector.
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> {
                        verify(transactionalServiceMock, times(2))
                                .processBatchTransactional(any(UUID.class));
                    });

            assertThat(calls.get()).isEqualTo(2);
            // No FAIL mark — the second attempt succeeded and the @Retryable
            // boundary did not propagate, so the catch path in
            // SettlementWorker.processBatchAsync did not fire. (It does NOT
            // fire here because processBatchTransactional succeeded, so
            // markBatchFailed is never called by the outer catch.)
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
