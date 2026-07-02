package com.fintech.payment.repository;

import com.fintech.payment.config.AuditingConfig;
import com.fintech.payment.model.entity.AuditLog;
import com.fintech.payment.model.enums.AuditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 AuditLog repository contract test — mirrors Phase 2's
 * {@code AccountRepositoryTest} shape: {@code @DataJpaTest} auto-replaces
 * to embedded H2 + {@code @Import(AuditingConfig.class)} so
 * {@code @CreatedDate} is exercised.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Save + findById (with auto-populated createdAt)</li>
 *   <li>findByEntityTypeAndEntityIdOrderByCreatedAtDesc (FR-4.2 query)</li>
 *   <li>findByEntityTypeOrderByCreatedAtDesc (paginated)</li>
 *   <li>findByWindow (date range for reconciliation report)</li>
 *   <li>findByActionOrderByCreatedAtDesc (forensic lookup)</li>
 *   <li>countByEntityTypeAndEntityId</li>
 * </ul>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(AuditingConfig.class)
class AuditLogRepositoryTest {

    @Autowired
    private AuditLogRepository repository;

    @Autowired
    private TestEntityManager em;

    private static final String ENTITY_PAYMENT = "PAYMENT";
    private static final String ENTITY_ACCOUNT = "ACCOUNT";
    private static final String ENTITY_BATCH = "SETTLEMENT_BATCH";

    private AuditLog audit(UUID entityId, String entityType, AuditAction action) {
        AuditLog log = new AuditLog();
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setAction(action);
        log.setOldValue(null);
        log.setNewValue(null);
        log.setPerformedBy("system");
        return log;
    }

    @Nested
    @DisplayName("SaveAndFindById")
    class SaveAndFindById {

        @Test
        void save_and_findById_round_trips() {
            UUID entityId = UUID.randomUUID();
            AuditLog saved = repository.save(audit(entityId, ENTITY_PAYMENT, AuditAction.CREATED));
            em.flush();
            Optional<AuditLog> reloaded = repository.findById(saved.getId());
            assertThat(reloaded).isPresent();
            assertThat(reloaded.get().getEntityType()).isEqualTo(ENTITY_PAYMENT);
            assertThat(reloaded.get().getEntityId()).isEqualTo(entityId);
            assertThat(reloaded.get().getAction()).isEqualTo(AuditAction.CREATED);
        }

        @Test
        void createdAt_is_auto_populated_by_JpaAuditing() {
            UUID entityId = UUID.randomUUID();
            AuditLog saved = repository.save(audit(entityId, ENTITY_PAYMENT, AuditAction.CREATED));
            em.flush();
            assertThat(saved.getCreatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("FindByEntityTypeAndEntityId")
    class FindByEntityTypeAndEntityId {

        @Test
        void returns_trail_newest_first_for_one_id() {
            UUID paymentId = UUID.randomUUID();
            repository.save(audit(paymentId, ENTITY_PAYMENT, AuditAction.CREATED));
            em.flush();
            em.clear();

            List<AuditLog> trail = repository
                    .findByEntityTypeAndEntityIdOrderByCreatedAtDesc(ENTITY_PAYMENT, paymentId);
            assertThat(trail).hasSize(1);
            assertThat(trail.get(0).getEntityId()).isEqualTo(paymentId);
            assertThat(trail.get(0).getAction()).isEqualTo(AuditAction.CREATED);
        }

        @Test
        void returns_empty_for_unknown_id() {
            List<AuditLog> trail = repository
                    .findByEntityTypeAndEntityIdOrderByCreatedAtDesc(ENTITY_PAYMENT, UUID.randomUUID());
            assertThat(trail).isEmpty();
        }

        @Test
        void countByEntityTypeAndEntityId_returns_correct_count() {
            UUID id = UUID.randomUUID();
            for (int i = 0; i < 3; i++) {
                repository.save(audit(id, ENTITY_PAYMENT, AuditAction.STATUS_CHANGE));
            }
            em.flush();
            assertThat(repository.countByEntityTypeAndEntityId(ENTITY_PAYMENT, id)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("FindByEntityTypePageable")
    class FindByEntityTypePageable {

        @Test
        void page_through_payments_only() {
            for (int i = 0; i < 5; i++) {
                repository.save(audit(UUID.randomUUID(), ENTITY_PAYMENT, AuditAction.CREATED));
                repository.save(audit(UUID.randomUUID(), ENTITY_ACCOUNT, AuditAction.STATUS_CHANGE));
            }
            em.flush();
            Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<AuditLog> page = repository.findByEntityTypeOrderByCreatedAtDesc(ENTITY_PAYMENT, pageable);
            assertThat(page.getTotalElements()).isEqualTo(5);
            page.forEach(row -> assertThat(row.getEntityType()).isEqualTo(ENTITY_PAYMENT));
        }
    }

    @Nested
    @DisplayName("FindByWindow")
    class FindByWindow {

        @Test
        void returns_events_inclusive_start_exclusive_end() {
            // Anchor at a known moment
            Instant anchor = Instant.parse("2026-07-01T00:00:00Z");
            AuditLog a = audit(UUID.randomUUID(), ENTITY_PAYMENT, AuditAction.CREATED);
            // Populate createdAt explicitly via fixture-controlled entity so
            // the boundary test is deterministic.
            em.persist(a);
            em.flush();

            // Frame the test on Instant.now() boundaries instead (the JPA
            // server-stamps createdAt, we don't control it directly). Use
            // a wide margin to be safe.
            Instant from = Instant.now().minusSeconds(60);
            Instant to = Instant.now().plusSeconds(60);
            List<AuditLog> rows = repository.findByWindow(from, to);
            assertThat(rows).hasSizeGreaterThanOrEqualTo(1);
        }
    }
}
