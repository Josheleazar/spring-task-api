package com.fintech.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Phase 4 retry configuration (SRS §11 + FR-3.4).
 *
 * <p>{@code @EnableRetry} registers the Spring AOP advice that activates
 * any {@code @Retryable} annotation on a public bean method. Without this
 * {@code @Configuration}, {@code @Retryable} is silently ignored.</p>
 *
 * <p>The retry policy itself lives at the call site — see
 * {@code SettlementWorker.processBatchAsync(@Retryable(...))} for the
 * concrete attempt count + backoff + {@code retryFor} shape.</p>
 *
 * <p>The Phase-1 drift log notes that {@code spring-retry-test} is not
 * published on Maven Central, so retry behaviour is exercised via Mockito
 * stubs (e.g. {@code verify(spy, times(3))....}) rather than
 * {@code RetryTemplate} test contexts. Re-add the dep once Spring Retry
 * publishes {@code spring-retry-test}.</p>
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
