package com.fintech.payment.integration;

import com.fintech.payment.exception.ChargeDeclinedException;
import com.fintech.payment.exception.PaymentGatewayTransientException;
import com.fintech.payment.model.entity.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 7 §12.7.1 — dev-profile {@link PaymentGatewayClient} mock.
 *
 * <p>Implements the abstract app-side seam with deterministic,
 * stateful, and configurable-failure modes so the full retry chain
 * can be exercised in CI without ever talking to Stripe:</p>
 *
 * <ul>
 *   <li><strong>Default mode</strong> (5% random failure rate):
 *       every 20th-invocations call randomly raises
 *       {@link PaymentGatewayTransientException} (~5% probability)
 *       so a happy-path integration test still observes retries
 *       occasionally.</li>
 *   <li><strong>Stateful mode</strong> ({@link #failNextNTimes(int)}):
 *       injects {@link PaymentGatewayTransientException} on the next
 *       N invocations, then succeeds. Used by
 *       {@code SettlementWorkerIT$RetryChain} to verify the
 *       {@code @Retryable(…retryFor = PaymentGatewayTransientException.class)}
 *       boundary exhaust-and-recover behaviour.</li>
 *   <li><strong>Hard-decline mode</strong> ({@link #declineNext()}):
 *       injects {@link ChargeDeclinedException} on the next
 *       invocation. Used by FR-3.4 tests to verify hard-decline
 *       skip-retry semantics.</li>
 * </ul>
 *
 * <p>The mock is thread-safe insofar as the fail-counter increments
 * atomically via {@link AtomicInteger}. Mock instances are
 * {@code @Profile("dev")} — Spring only loads them when the dev
 * profile is active (the {@code spring.profiles.default: dev} in
 * {@code application.yml} means tests pick them up by default).
 * Production-shape {@code RealStripeClient} is documented-only;
 * this is the seam the Phase-8 production showcase fills in.</p>
 */
@Component
@Profile("dev")
@Slf4j
public class MockStripeClient implements PaymentGatewayClient {

    /** Default 5% random failure rate (FR-6.3 specifies 95% success). */
    private static final double DEFAULT_FAILURE_RATE = 0.05;

    /** Random failure rate per {@link #charge(Payment)} invocation. */
    private final double failureRate;

    /** Remaining synthetic transient failures to inject; 0 = no injection. */
    private final AtomicInteger transientFailuresRemaining = new AtomicInteger(0);

    /** Remaining synthetic hard-decline failures to inject; 0 = no injection. */
    private final AtomicInteger declinesRemaining = new AtomicInteger(0);

    /** Total successful charges (observable counter for tests). */
    private final AtomicInteger successCount = new AtomicInteger(0);

    public MockStripeClient() {
        this(DEFAULT_FAILURE_RATE);
    }

    /** Test constructor — lets the test specify a custom failure rate. */
    public MockStripeClient(double failureRate) {
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException("failureRate must be in [0, 1] (got " + failureRate + ")");
        }
        this.failureRate = failureRate;
    }

    /** Inject a deterministic transient-fault budget (consumed FIFO by charge()). */
    public void failNextNTimes(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0");
        transientFailuresRemaining.addAndGet(n);
        log.info("MockStripeClient queued {} transient-fault injections (remaining={})",
                n, transientFailuresRemaining.get());
    }

    /** Inject a deterministic hard-decline budget (consumed FIFO by charge()). */
    public void declineNext() {
        declinesRemaining.incrementAndGet();
        log.info("MockStripeClient queued 1 hard-decline (remaining={})",
                declinesRemaining.get());
    }

    @Override
    public ChargeResult charge(Payment payment) {
        // Decline path — consumed before transient path so a decline
        // test isn't accidentally trimmed by a transient budget.
        if (declinesRemaining.get() > 0) {
            declinesRemaining.decrementAndGet();
            log.warn("MockStripeClient declining charge paymentId={} amount={} (hard-decline injection)",
                    payment.getId(), payment.getAmount());
            throw new ChargeDeclinedException("simulated hard decline for paymentId=" + payment.getId());
        }
        // Stateful transient path.
        if (transientFailuresRemaining.get() > 0) {
            transientFailuresRemaining.decrementAndGet();
            log.warn("MockStripeClient injecting transient fault paymentId={} amount={} (remaining={})",
                    payment.getId(), payment.getAmount(), transientFailuresRemaining.get());
            throw new PaymentGatewayTransientException(
                    "simulated transient fault for paymentId=" + payment.getId());
        }
        // Random-path fault.
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            log.warn("MockStripeClient random transient fault paymentId={} amount={}",
                    payment.getId(), payment.getAmount());
            throw new PaymentGatewayTransientException(
                    "random transient fault for paymentId=" + payment.getId());
        }
        ChargeResult result = ChargeResult.success();
        successCount.incrementAndGet();
        log.debug("MockStripeClient successful charge paymentId={} gatewayId={}",
                payment.getId(), result.gatewayChargeId());
        return result;
    }

    @Override
    public List<ChargeResult> batchCharge(List<Payment> payments) {
        List<ChargeResult> out = new ArrayList<>(payments.size());
        for (Payment p : payments) {
            out.add(charge(p));
        }
        return out;
    }

    /** Test helper — number of successful charges since mock construction. */
    public int successCount() {
        return successCount.get();
    }
}
