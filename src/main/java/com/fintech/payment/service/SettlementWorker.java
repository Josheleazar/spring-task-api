package com.fintech.payment.service;

import com.fintech.payment.exception.PaymentGatewayTransientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase 4 settlement worker — covers FR-3.3 (@Async execution) + FR-3.4
 * (Spring Retry on {@link OptimisticLockingFailureException}, the §12.3.3
 * deferred item).
 *
 * <h2>Architecture (§12.4.self-invocation-defect)

Originally this class held both the {@code @Async} + {@code @Retryable}
 * {@link #processBatchAsync} method AND its {@code @Transactional} helper.
 * The Phase-4 reviewer flagged this as the same defect class as Phase-2
 * §12.2.1's self-invocation b
ypass — a {@code @Transactional} method called
 * via {@code this.…} from the same class runs without a tx because the
 * AOP proxy is bypassed. The fix: extract the transactional helpers into
 * {@link SettlementTransactionalService}, which this class now injects.
 * Cross-bean invocation correctly activates the proxy.</h2>
 *
 * <p>The retryable exception is just {@link OptimisticLockingFailureException}
 * today (Phase-4 KISS; {@code PaymentGatewayClient} is deferred to Phase 7
 * per the deferred-II item list). {@code maxAttempts=3}, {@code @Backoff(delay=500)}
 * — enough j
itter to skirt most CAS contention without delaying completion
 * noticeably.</p>
 *
 * <p>{@link #processBatchAsync} returns {@code void}. Outcomes are
 * observable via the GET /{id} polling path.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementWorker {

    private final SettlementTransactionalService transactionalService;

    /**
     * Async + Retryable entry point. Delegates to the transactional helper
     * bean — cross-bean invocation activates the {@code @Transactional} proxy.
     */
    /**
     * Async + Retryable entry point. Delegates to the transactional helper
     * bean — cross-bean invocation correctly activates the {@code @Transactional} proxy.
     * * <h2>Phase 7 §12.6.1 closure — exception-swallowing defect fix</h2>
 *
 * <p>The pre-Phase-7 shape wrapped the inner call in a
 * {@code try { … } catch (RuntimeException ex)} block that logged
 * the supposed exhaustion and called {@code markBatchFailed} on the
 * first attempt. This silently defeated {@code @Retryable}: Spring
 * Retry's {@code RetryOperationsInterceptor} sits OUTSIDE the method
 * body, so any {@code RuntimeException} swallowed inside the method
 * never reaches the interceptor, and {@code maxAttempts} is
 * effectively {@code 1} — the {@code markBatchFailed} path always
 * wins, regardless of whether the inner cause is recoverable.</p>
 *
 * <p>This is the same defect *family* as the Phase-2 §12.2.1
 * self-invocation defect: a wrong boundary on the cross-cutting
 * concern silently disables its semantics. §12.2.1 was specifically
 * the @Transactional proxy bypass; this is the @Retryable
 * exception-boundary swallow. Both are AOP-interceptor-boundary
 * defects. Resolution:</p>
 * <ul>
 *   <li>Drop the in-method {@code try/catch}. Let the OLF cross the
 *       method boundary so {@code @Retryable} sees it.</li>
 *   <li>Recover the exhaustion path with a SIBLING
 *       {@link Recover @Recover} method (Spring Retry's idiomatic
 *       pattern). {@code @Recover} is dispatched by the
 *       {@code RetryOperationsInterceptor} after retries are
 *       exhausted, so the recovery logic no longer competes with the
 *       retry boundary.</li>
 * </ul>
 *
 * <h2>Phase 7 §12.7.1 FR-3.4 expansion — gateway transient retry</h2>
 *
 * <p>Retry boundary is now expanded to include {@code
 * PaymentGatewayTransientException} (FR-3.4 deferred-II item). The
 * Spring Retry dispatch on {@code retryFor} matches a thrown exception
 * instance against any of the listed types — so a transient-fault
 * from {@code PaymentGatewayClient.charge()} will trigger the same
 * @Backoff(500) retry loop as a CAS-race OLF.</p>
 *
 * <p><strong>Critical Spring-Retry @Recover dispatch rule:</strong>
 * recovery methods are dispatched STRICTLY by their first parameter
 * type. {@code recoverFromOptimisticLockingFailure} handles OLF only;
 * a thrown {@code PaymentGatewayTransientException} will NOT be
 * caught by it. Any future addition to {@code retryFor} MUST come
 * with a sibling {@code @Recover} method whose first parameter is
 * exactly that exception type, otherwise the retry-exhaustion path
 * falls into Spring's default uncaught-@Async exception handler and
 * silently marks the batch as PROCESSING rather than FAILED.</p>
 */
    @Async("settlementWorkerExecutor")
    @Retryable(
            retryFor = {
                    OptimisticLockingFailureException.class,
                    PaymentGatewayTransientException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 500))
    public void processBatchAsync(UUID batchId) {
        log.info("SettlementWorker starting batchId={}", batchId);
        // NO try/catch — the @Retryable interceptor must see the
        // exception cross the method boundary for backoff + retry to
        // fire. Recovery (incl. markBatchFailed) is dispatched by the
        // sibling @Recover methods below.
        transactionalService.processBatchTransactional(batchId);
    }

    /**
     * Spring Retry {@code @Recover} hook — dispatched by
     * {@code RetryOperationsInterceptor} after {@code maxAttempts} is
     * exhausted on {@link #processBatchAsync(UUID)} for an
     * {@link OptimisticLockingFailureException}.
     *
     * <p>The method signature must satisfy Spring Retry's recover-method
     * contract: first parameter is the recoverable exception (matched
     * to {@code retryFor}), followed by the original method's parameters
     * in order. Dispatched by the interceptor — bypasses {@code @Async}
     * (good: synchronous on the worker thread so {@code markBatchFailed}'s
     * {@code @Transactional(REQUIRES_NEW)} runs in its own tx).</p>
     */
    @Recover
    public void recoverFromOptimisticLockingFailure(
            OptimisticLockingFailureException ex, UUID batchId) {
        log.warn("SettlementWorker exhausted retries on batchId={} (cause={}): {}",
                batchId, "OptimisticLockingFailureException", ex.toString());
        transactionalService.markBatchFailed(batchId, ex.getMessage());
    }

    /**
     * Spring Retry {@code @Recover} hook — Phase 7 §12.7.1 FR-3.4
     * gateway expansion. Sibling to {@link
     * #recoverFromOptimisticLockingFailure}, dispatched by
     * {@code RetryOperationsInterceptor} when retry exhaustion happens
     * on a {@link PaymentGatewayTransientException} (gateway transient
     * fault such as a Stripe 5xx or a connection-reset).
     *
     * <p>Spring Retry dispatches recover methods by FIRST PARAMETER
     * type — a thrown {@code PaymentGatewayTransientException} will
     * NOT be caught by {@code recoverFromOptimisticLockingFailure}
     * (OLF) because the parameter types differ. This method MUST
     * remain in the class as long as {@code retryFor} includes
     * {@code PaymentGatewayTransientException.class}.</p>
     */
    @Recover
    public void recoverFromPaymentGatewayTransient(
            PaymentGatewayTransientException ex, UUID batchId) {
        log.warn("SettlementWorker exhausted retries on batchId={} (cause={}): {}",
                batchId, "PaymentGatewayTransientException", ex.toString());
        transactionalService.markBatchFailed(batchId, ex.getMessage());
    }
}
