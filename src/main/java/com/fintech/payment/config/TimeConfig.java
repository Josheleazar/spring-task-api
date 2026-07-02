package com.fintech.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Phase 4 time infrastructure — exposes a single inject-able {@link Clock}
 * bean so {@code @Scheduled} tick paths (SettlementService daily-create,
 * IdempotencyCleanupJob hourly-purge, SettlementWorker.failure-stamp) can
 * be exercised against a fixed {@code Clock.fixed(...)} in unit tests.
 *
 * <p>Spring Boot does not auto-configure a {@code Clock} bean. Without
 * one, services would call {@code LocalDate.now()} and
 * {@code Instant.now()} directly — incompatible with deterministic
 * test fixtures.</p>
 */
@Configuration
public class TimeConfig {

    /**
     * Production bean — UTC system clock. {@code Clock} is an immutable
     * abstraction; tests can override with {@code @MockBean Clock}.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
