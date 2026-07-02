package com.fintech.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Payment Settlement API.
 *
 * <p>Bootstraps the Spring context. Async execution is enabled via
 * {@link com.fintech.payment.config.AsyncConfig} so all async-related
 * configuration lives together. Scheduling is enabled here so controllers
 * and services are free of cross-cutting annotations. AOP is enabled here so
 * the cross-cutting {@link com.fintech.payment.audit.AuditAspect} Phase-5
 * interceptor is wired without per-bean @EnableAspectJAutoProxy annotations.
 */
@SpringBootApplication
@EnableScheduling
@EnableAspectJAutoProxy
public class PaymentSettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentSettlementApplication.class, args);
    }
}
