package com.fintech.payment.service;

import com.fintech.payment.model.entity.SettlementBatch;
import com.fintech.payment.model.enums.AuditAction;
import com.fintech.payment.model.enums.SettlementStatus;
import com.fintech.payment.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6 SettlementService driver test — covers §12.6.1 item (1).
 *
 * <p>Strategy: load the real {@link SettlementService} (with all its
 * production wiring), stub the {@link SettlementWorker} /
 * {@link AuditService} via MockitoBean so we can:
 * <ul>
 *   <li>verify a {@code BATCH_OPEN} audit row is emitted on the happy
 *       path (Phase 5 baseline behaviour);</li>
 *   <li>trigger a {@link RejectedExecutionException} from the worker
 *       and assert a {@code BATCH_REJECTED} audit row is emitted instead
 *       of silently leaving the batch row stranded.</li>
 * </ul>
 *
 * <p>The Clock bean is fixed to {@code 2026-07-04T00:00:00Z} so the
 * {@code createDailyBatch} tick's {@code LocalDate.now(clock)} is
 * deterministic across CI runs.</p>
 */
@DataJpaTest
@Import({SettlementService.class, com.fintech.payment.config.TimeConfig.class,
        com.fintech.payment.config.AuditingConfig.class,
        com.fintech.payment.service.AuditService.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.task.scheduling.pool.size=4"
})
class SettlementServiceTest {

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementRepository settlementRepository;

    @MockBean
    private SettlementWorker settlementWorker;

    @MockBean
    private com.fintech.payment.service.AuditService auditService;

    /**
     * SettlementService injects a {@code Clock} bean for deterministic
     * date math in the daily-tick. We override the bean with a fixed
     * clock so the test asserts against {@code 2026-07-04} (the chosen
     * fixture date) rather than the wall-clock date.
     */
    @MockBean
    private Clock clock;

    @BeforeEach
    void resetState() {
        settlementRepository.deleteAll();
        org.mockito.Mockito.reset(settlementWorker, auditService, clock);
        // Default: Clock returns 2026-07-04. Tests override if needed.
        when(clock.instant()).thenReturn(Instant.parse("2026-07-04T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Nested
    @DisplayName("CreateDailyBatch")
    class CreateDailyBatch {

        @Test
        void happy_path_persists_OPEN_batch_and_emits_BATCH_OPEN_audit() {
            settlementService.createDailyBatch();

            // One OPEN batch row exists
            var batches = settlementRepository.findAll();
            assertThat(batches).hasSize(1);
            SettlementBatch batch = batches.get(0);
            assertThat(batch.getStatus()).isEqualTo(SettlementStatus.OPEN);
            // Clock is mocked to 2026-07-04T00:00:00Z (BeforeEach), so the
            // createDailyBatch tick stamps the batch with that date.
            assertThat(batch.getBatchDate()).isEqualTo(LocalDate.parse("2026-07-04"));

            // BATCH_OPEN audit row emitted
            verify(auditService, times(1)).record(
                    AuditAction.BATCH_OPEN,
                    "SETTLEMENT_BATCH",
                    batch.getId(),
                    null,
                    null,
                    "system");

            // Worker dispatched once
            verify(settlementWorker, times(1)).processBatchAsync(batch.getId());
        }

        @Test
        void worker_rejection_emits_BATCH_REJECTED_audit_and_keeps_row_OPEN() {
            // Pre-arm the worker to throw RejectedExecutionException — simulates
            // a queue overflow on settlementWorkerExecutor under burst load.
            doThrow(new RejectedExecutionException("queue full"))
                    .when(settlementWorker).processBatchAsync(any(UUID.class));

            settlementService.createDailyBatch();

            // The OPEN batch row STILL exists (no rollback on worker-rejection path)
            var batches = settlementRepository.findAll();
            assertThat(batches).hasSize(1);
            SettlementBatch batch = batches.get(0);
            assertThat(batch.getStatus()).isEqualTo(SettlementStatus.OPEN);

            // BATCH_OPEN audit emitted (pre-worker call)
            verify(auditService, times(1)).record(
                    AuditAction.BATCH_OPEN,
                    "SETTLEMENT_BATCH",
                    batch.getId(),
                    null,
                    null,
                    "system");

            // BATCH_REJECTED audit emitted (post-worker-throw) with the exception
            // message in newValue
            verify(auditService, times(1)).record(
                    org.mockito.ArgumentMatchers.eq(AuditAction.BATCH_REJECTED),
                    org.mockito.ArgumentMatchers.eq("SETTLEMENT_BATCH"),
                    org.mockito.ArgumentMatchers.eq(batch.getId()),
                    org.mockito.ArgumentMatchers.isNull(),
                    anyString(),    // the exception message
                    org.mockito.ArgumentMatchers.eq("system"));
        }
    }
}
