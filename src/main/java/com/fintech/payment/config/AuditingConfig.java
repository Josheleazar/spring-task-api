package com.fintech.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * Enables Spring Data JPA auditing so entities can use
 * {@code @CreatedDate}, {@code @LastModifiedDate} and {@code @LastModifiedBy}.
 *
 * <p>The default auditor returns {@code "system"}. When authentication is added
 * (Phase 8+), replace this bean with one resolving the current principal from
 * the SecurityContext.
 */
@Configuration
@EnableJpaAuditing
public class AuditingConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> Optional.of("system");
    }
}
