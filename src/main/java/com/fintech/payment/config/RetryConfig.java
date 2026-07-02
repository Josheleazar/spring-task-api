package com.fintech.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry so services can annotate methods with
 * {@code @Retryable} / {@code @Recover}. Used by settlement batch
 * processing (FR-3.4).
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
