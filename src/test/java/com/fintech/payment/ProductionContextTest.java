package com.fintech.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 — Profile-switching test (§12.6.1 item 7).
 *
 * <p>Verifies that activating the {@code prod} profile loads an
 * {@code ApplicationContext} without throwing on beans that the
 * production shape exposes (e.g., {@code HikariDataSource}, Postgres
 * dialect). The {@code @SpringBootTest} Matrix pattern would normally
 * require a real Postgres, but for this assertion we override the
 * datasource back to H2 so the context can resolve while still
 * exercising the production-shaped {@code application-prod.yml}
 * loading.</p>
 *
 * <p>Property assertions on top:</p>
 * <ul>
 *   <li>ACTIVE profile = "prod"</li>
 *   <li>open-in-view = false (preserved from base application.yml)</li>
 *   <li>datasource override wins over prod yaml</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        // Override datasource to H2 so context can load in CI without Postgres;
        // the override is a TEST-ONLY substitution — production deployment
        // uses application-prod.yml's real Postgres URL.
        "spring.datasource.url=jdbc:h2:mem:prod-context-test",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Disable scheduled tasks so the @Scheduled createDailyBatch tick does
        // not fire on context-start and pollute the audit log row count.
        "spring.scheduling.enabled=false",
        // Disable async executor initialisation so the @Async advisor does
        // not try to spin up a worker thread during context-load.
        "spring.task.execution.pool.core-size=0",
        "spring.task.execution.pool.max-size=0"
})
class ProductionContextTest {

    @Autowired
    private Environment env;

    @Test
    @DisplayName("production profile loads — application-prod.yml parses cleanly")
    void prod_profile_loads_and_binds_expected_properties() {
        // 1. The active profile is 'prod'.
        assertThat(env.getActiveProfiles()).contains("prod");

        // 2. JPA — ddl-auto from base yml ('validate') is overridden by test override.
        // Verifies that the test override mechanism works AND that the prod yml
        // didn't accidentally pin a value that would block the override.
        String ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto");
        assertThat(ddlAuto).isEqualTo("create-drop");

        // 3. The Datasource override is in effect.
        String url = env.getProperty("spring.datasource.url");
        assertThat(url).contains("jdbc:h2:mem:prod-context-test");

        // 4. JPA open-in-view is false (inherited from base yml — Phase-1
        // invariant: lazy loading must never happen in controllers).
        String oiv = env.getProperty("spring.jpa.open-in-view");
        assertThat(oiv).isEqualTo("false");

        // 5. Application name still resolves (proves spring.application.name
        // in base yml is honoured under prod profile).
        assertThat(env.getProperty("spring.application.name")).isEqualTo("payment-settlement-api");
    }
}
