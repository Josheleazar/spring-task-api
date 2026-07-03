package com.fintech.payment.integration;

import com.fintech.payment.model.entity.Payment;
import com.fintech.payment.repository.PaymentRepository;
import com.fintech.payment.model.enums.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Phase 7 §12.7.1 — dev-profile {@link FraudDetectionClient} mock.
 *
 * <p>Implements two rule patterns out of the box (FR-6.4):</p>
 * <ul>
 *   <li><strong>amount-threshold</strong>: any payment with
 *       {@code amount > thresholdAmount} is flagged. Threshold
 *       defaults to {@link #DEFAULT_AMOUNT_THRESHOLD} (10 000).
 *       Configurable via {@link #MockSiftClient(BigDecimal, int, Duration)}.</li>
 *   <li><strong>velocity</strong>: more than {@code limitPerWindow}
 *       payments with the same {@code sourceAccountId} in the last
 *       {@code windowSeconds} are flagged. Defaults: 3 / 60s.</li>
 * </ul>
 *
 * <p>The mock's default behaviour is {@link FraudScore#clean()} — i.e.
 * <em>no</em> rules trip on the canonical Phase-3 test fixtures, so the
 * 27 Phase-3 {@code PaymentControllerTest}/{@code PaymentRepositoryTest}
 * slice continues to pass without modification. Tests can override the
 * thresholds or call {@link #flagNextAsAmountThreshold()} to inject a
 * synthetic trip for fraud-block envelope verification.</p>
 *
 * <p>The mock consults the {@code PaymentRepository} directly for the
 * velocity count — under the @Profile dev / H2 setup this is a
 * straightforward {@code findBySourceAccountId + status filter}; the
 * real {@code RealSiftClient} in Phase 8 will talk to a vendor endpoint
 * rather than the ledger.</p>
 */
@Component
@Profile("dev")
@Slf4j
public class MockSiftClient implements FraudDetectionClient {

    static final BigDecimal DEFAULT_AMOUNT_THRESHOLD = new BigDecimal("10000.00");

    static final int DEFAULT_LIMIT_PER_WINDOW = 3;
    static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);

    private final BigDecimal amountThreshold;
    private final int limitPerWindow;
    private final Duration window;

    /** Force a single fake-fraud trip on the next {@link #score} call. */
    private volatile boolean flagNextCall = false;

    private final PaymentRepository paymentRepository;
    private final Clock clock;

    /**
     * Spring-wiring constructor (auto-picked via {@code @Autowired}).
     * NOTE: the class intentionally exposes a 5-arg configuration
     * constructor for tests, so Spring needs an explicit hint to choose
     * this one for dependency injection — otherwise the multi-arg
     * ambiguity throws "No default constructor found" at startup.
     */
    @Autowired
    public MockSiftClient(PaymentRepository paymentRepository, Clock clock) {
        this(paymentRepository, clock,
                DEFAULT_AMOUNT_THRESHOLD, DEFAULT_LIMIT_PER_WINDOW, DEFAULT_WINDOW);
    }

    /** Test / configuration constructor — explicitly set thresholds. */
    public MockSiftClient(PaymentRepository paymentRepository, Clock clock,
                          BigDecimal amountThreshold, int limitPerWindow, Duration window) {
        this.paymentRepository = paymentRepository;
        this.clock = clock;
        this.amountThreshold = amountThreshold;
        this.limitPerWindow = limitPerWindow;
        this.window = window;
        log.info("Initialised MockSiftClient (amountThreshold={}, limitPerWindow={}, window={})",
                amountThreshold, limitPerWindow, window);
    }

    /** Trigger a one-shot synthetic amount-threshold rule-trip on the next call. */
    public void flagNextAsAmountThreshold() {
        this.flagNextCall = true;
    }

    @Override
    public FraudScore score(Payment payment) {
        // Manual injection for tests that want to assert the FraudDetected envelope.
        if (flagNextCall) {
            flagNextCall = false;
            log.warn("MockSiftClient synthetic rule-trip paymentId={} amount={} (threshold={})",
                    payment.getId(), payment.getAmount(), amountThreshold);
            return FraudScore.amountThreshold(payment.getAmount(), amountThreshold);
        }
        // Rule 1 — amount-threshold.
        if (payment.getAmount() != null && payment.getAmount().compareTo(amountThreshold) > 0) {
            log.warn("MockSiftClient amount-threshold trip paymentId={} amount={} threshold={}",
                    payment.getId(), payment.getAmount(), amountThreshold);
            return FraudScore.amountThreshold(payment.getAmount(), amountThreshold);
        }
        // Rule 2 — velocity window per source account.
        Instant windowStart = Instant.now(clock).minus(window);
        long recentCount = paymentRepository
                .findBySourceAccountIdAndStatusAndCreatedAtAfter(
                        payment.getSourceAccountId(), PaymentStatus.COMPLETED, windowStart)
                .size();
        if (recentCount >= limitPerWindow) {
            log.warn("MockSiftClient velocity-window trip paymentId={} source={} count={} limit={}",
                    payment.getId(), payment.getSourceAccountId(), recentCount, limitPerWindow);
            return FraudScore.velocityWindow((int) recentCount, (int) window.getSeconds(), limitPerWindow);
        }
        return FraudScore.clean();
    }
}
