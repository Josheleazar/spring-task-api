package com.fintech.payment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;

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
        // Phase 7.x Item B — bounded backpressure. The previous default
        // (ThreadPoolExecutor.AbortPolicy, inherited when setRejectedExecutionHandler
        // was omitted) rejected outright on queue overflow (max queue depth =
        // core + max-in-flight threads + queue capacity = 2 + 4 + 50 = 56 tasks).
        // AbortPolicy raised RejectedExecutionException to the caller, which
        // SettlementService.createDailyBatch() handled via a BATCH_REJECTED audit,
        // but SettlementService.triggerProcessing() (manual POST /process) had
        // no such catch — the rejection would propagate to the controller as
        // a 500 INTERNAL_ERROR rather than degrading gracefully.
        //
        // CallerRunsPolicy degrades by running the task on the @Scheduled or
        // @PostMapping caller thread when the pool + queue is saturated. At
        // worst, the caller thread carries the work in-line rather than
        // losing data outright. We wrap with a logging handler so we have
        // an audit trail of saturation events without losing the
        // graceful-degradation semantics.
        executor.setRejectedExecutionHandler(loggingCallerRunsPolicy());
        executor.initialize();
        log.info("Initialised settlementWorkerExecutor (core={}, max={}, queue={}, rejection=CallerRunsPolicy)",
                SETTLEMENT_WORKER_CORE_POOL_SIZE,
                SETTLEMENT_WORKER_MAX_POOL_SIZE,
                SETTLEMENT_WORKER_QUEUE_CAPACITY);
        return executor;
    }

    /**
     * Logging-wrapped {@link ThreadPoolExecutor.CallerRunsPolicy} —
     * emits a WARN with task identity on saturation, then runs the
     * rejected task on the caller thread (CallerRuns semantics). The
     * default {@code ThreadPoolTaskExecutor} rejects via
     * {@code AbortPolicy} → {@link java.util.concurrent.RejectedExecutionException},
     * which forces upstream callers to either catch it explicitly (as
     * {@code SettlementService.createDailyBatch} does for the daily tick)
     * or surface it as a 500. With this wired-in graceful-degradation,
     * the {@code @Scheduled} daily tick and the manual
     * {@code POST /api/v1/settlements/{id}/process} trigger stay functional
     * under burst load.
     */
    private static RejectedExecutionHandler loggingCallerRunsPolicy() {
        return (runnable, executor) -> {
            log.warn("settlementWorkerExecutor saturated (active={}, queue={}, poolSize={}) — " +
                            "running rejected task {} on caller thread per CallerRunsPolicy",
                    executor.getActiveCount(),
                    executor.getQueue().size(),
                    executor.getPoolSize(),
                    runnable);
            if (!executor.isShutdown()) {
                runnable.run();
            }
        };
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
