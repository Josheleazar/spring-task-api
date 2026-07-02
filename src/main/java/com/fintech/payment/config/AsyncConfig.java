package com.fintech.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables {@code @Async} and supplies a dedicated executor for settlement
 * processing (FR-3.3).
 *
 * <p>A named executor keeps settlement work isolated from any other async
 * traffic. Values are conservative defaults matched to the
 * "API responds <500ms" non-functional requirement.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String SETTLEMENT_EXECUTOR = "settlementExecutor";

    @Bean(name = SETTLEMENT_EXECUTOR)
    public Executor settlementExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("settlement-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
