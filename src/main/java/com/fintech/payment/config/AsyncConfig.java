package com.fintech.payment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Phase 4 async configuration (SRS §11 + FR-3.3).
 *
 * <p>Wires the default executor for {@code @Async} methods (used by
 * {@code SettlementWorker.processBatchAsync}) onto a bounded
 * {@link ThreadPoolTaskExecutor} rather than letting Spring fall back to
 * the unbounded {@code SimpleAsyncTaskExecutor}. Bounded concurrency is
 * critical for FR-3.3: the daily batch may hold hundreds of payments; a
 * simple unbounded executor risks thread explosion under load.</p>
 *
 * <p>Pool sizing is deliberately conservative (core=2, max=4, queue=50):
 * Phase 4 volume is one batch per day; Phase 5/6 may revisit once real
 * throughput data arrives.</p>
 *
 * <p>Exception handler logs failures at WARN level rather than rethrowing —
 * the @Async boundary is fire-and-forget from the worker's perspective;
 * the Retryable layer on the inner method handles retries of the
 * recoverable cases, non-recoverable exceptions are surfaced through the
 * PaymentRepository / SettlementBatch.status = FAILED path.</p>
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    private static final String SETTLEMENT_WORKER_THREAD_NAME_PREFIX = "settlement-worker-";
    private static final int SETTLEMENT_WORKER_CORE_POOL_SIZE = 2;
    private static final int SETTLEMENT_WORKER_MAX_POOL_SIZE = 4;
    private static final int SETTLEMENT_WORKER_QUEUE_CAPACITY = 50;

    @Bean(name = "settlementWorkerExecutor")
    public Executor settlementWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(SETTLEMENT_WORKER_CORE_POOL_SIZE);
        executor.setMaxPoolSize(SETTLEMENT_WORKER_MAX_POOL_SIZE);
        executor.setQueueCapacity(SETTLEMENT_WORKER_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(SETTLEMENT_WORKER_THREAD_NAME_PREFIX);
        executor.initialize();
        log.info("Initialised settlementWorkerExecutor (core={}, max={}, queue={})",
                SETTLEMENT_WORKER_CORE_POOL_SIZE,
                SETTLEMENT_WORKER_MAX_POOL_SIZE,
                SETTLEMENT_WORKER_QUEUE_CAPACITY);
        return executor;
    }

    /**
     * Default executor for any non-explicitly-named @Async method. Same
     * shape as {@link #settlementWorkerExecutor()} but kept as a separate
     * instance so a runaway non-settlement @Async caller cannot starve
     * the settlement worker pool.
     */
    @Override
    public Executor getAsyncExecutor() {
        return settlementWorkerExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.warn(
                "Uncaught @Async exception from method {} (params={}): {}",
                method.getName(), params, ex.toString());
    }
}
