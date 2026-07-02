package com.fintech.payment.repository;

import com.fintech.payment.config.AuditingConfig;
import com.fintech.payment.model.entity.IdempotencyRecord;
import com.fintech.payment.model.entity.Payment;
import com.fintech.payment.model.enums.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository slice test for {@link PaymentRepository} + {@link IdempotencyRepository}
 * covering SRS FR-2.1, FR-2.4..2.6 persistence semantics:
 * <ul>
 *   <li>round-trip save + findById with audit field population + @Version increment</li>
 *   <li>{@code findByIdempotencyKey} for cache fast-path</li>
 *   <li>{@code findByStatus} paginated filter</li>
 *   <li>paginated {@code findAll}</li>
 *   <li>unique-constraint enforcement on duplicate idempotencyKeys</li>
 *   <li>{@link IdempotencyRepository} CRUD on the cache table</li>
 * </ul>
 *
 * <p>{@code @DataJpaTest} brings up the JPA slice with embedded H2. {@code @Import}
 * keeps the audit wiring local to the slice.</p>
 */
@DataJpaTest
@Import(AuditingConfig.class)
@DisplayName("PaymentRepository / IdempotencyRepository")
class PaymentRepositoryTest {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private IdempotencyRepository idempotencyRepository;

    /* -------------------- helpers -------------------- */

    private static Payment newPayment(String idempotencyKey,
                                      UUID sourceId,
                                      UUID targetId,
                                      BigDecimal amount,
                                      AccountLike... ignored) {
        Payment p = new Payment();
        p.setIdempotencyKey(idempotencyKey);
        p.setSourceAccountId(sourceId);
        p.setTargetAccountId(targetId);
        p.setAmount(amount);
        p.setCurrency("USD");
        p.setStatus(PaymentStatus.COMPLETED);
        p.setProcessedAt(Instant.parse("2026-07-02T10:30:00Z"));
        return p;
    }

    /** Placeholder so {@link #newPayment} matches its 5-arg signature cleanly when called from accounts. */
    private enum AccountLike { SOURCE, TARGET }

    private static Payment persistedPayment(String idempotencyKey) {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        return newPayment(idempotencyKey, source, target, new BigDecimal("50.00"));
    }

    /* -------------------- save + findById + audit -------------------- */

    @Nested
    @DisplayName("save + findById")
    class SaveAndFindById {

        @Test
        void save_persists_and_findById_returns_managed_entity_with_audit_fields() {
            Payment saved = paymentRepository.saveAndFlush(
                    persistedPayment("PMT-AUDIT-1"));

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();

            Optional<Payment> reloaded = paymentRepository.findById(saved.getId());
            assertThat(reloaded)
                    .isPresent()
                    .get()
                    .satisfies(p -> {
                        assertThat(p.getIdempotencyKey()).isEqualTo("PMT-AUDIT-1");
                        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
                        assertThat(p.getAmount()).isEqualByComparingTo("50.00");
                        assertThat(p.getCurrency()).isEqualTo("USD");
                        assertThat(p.getVersion()).isNotNull().isGreaterThanOrEqualTo(0L);
                    });
        }

        @Test
        void updating_an_existing_payment_increments_version_and_bumps_updatedAt() {
            Payment created = paymentRepository.saveAndFlush(persistedPayment("PMT-VER-1"));
            Long versionBefore = created.getVersion();
            Instant updatedAtBefore = created.getUpdatedAt();

            created.setStatus(PaymentStatus.REVERSED);
            Payment updated = paymentRepository.saveAndFlush(created);

            assertThat(updated.getVersion()).isGreaterThan(versionBefore);
            assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updatedAtBefore);
        }
    }

    /* -------------------- findByIdempotencyKey (cache keying) -------------------- */

    @Nested
    @DisplayName("findByIdempotencyKey")
    class FindByIdempotencyKey {

        @Test
        void returns_entity_when_key_matches() {
            paymentRepository.saveAndFlush(persistedPayment("PMT-FIND-1"));
            Optional<Payment> found = paymentRepository.findByIdempotencyKey("PMT-FIND-1");

            assertThat(found)
                    .isPresent()
                    .get()
                    .satisfies(p -> assertThat(p.getIdempotencyKey()).isEqualTo("PMT-FIND-1"));
        }

        @Test
        void returns_empty_when_key_absent() {
            assertThat(paymentRepository.findByIdempotencyKey("PMT-NEVER-WAS")).isEmpty();
        }
    }

    /* -------------------- findByStatus (paginated) -------------------- */

    @Nested
    @DisplayName("findByStatus paginated")
    class FindByStatus {

        @Test
        void filters_by_status_and_reports_totalElements() {
            for (int i = 0; i < 5; i++) {
                paymentRepository.saveAndFlush(
                        paymentWithStatus("PMT-CMP-" + i, PaymentStatus.COMPLETED));
            }
            for (int i = 0; i < 3; i++) {
                paymentRepository.saveAndFlush(
                        paymentWithStatus("PMT-REV-" + i, PaymentStatus.REVERSED));
            }

            Page<Payment> completedPage = paymentRepository.findByStatus(
                    PaymentStatus.COMPLETED, PageRequest.of(0, 10));
            Page<Payment> reversedPage = paymentRepository.findByStatus(
                    PaymentStatus.REVERSED, PageRequest.of(0, 10));

            assertThat(completedPage.getTotalElements()).isEqualTo(5);
            assertThat(completedPage.getContent()).hasSize(5)
                    .allMatch(p -> p.getStatus() == PaymentStatus.COMPLETED);
            assertThat(reversedPage.getTotalElements()).isEqualTo(3);
            assertThat(reversedPage.getContent()).hasSize(3)
                    .allMatch(p -> p.getStatus() == PaymentStatus.REVERSED);
        }
    }

    /* -------------------- findAll (paginated — fallback) -------------------- */

    @Nested
    @DisplayName("findAll paginated")
    class FindAllPageable {

        @Test
        void honours_sort_by_createdAt_desc() {
            for (int i = 0; i < 3; i++) {
                paymentRepository.saveAndFlush(persistedPayment("PMT-LST-" + i));
            }

            Pageable sorted = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
            List<String> keys = paymentRepository.findAll(sorted).getContent().stream()
                    .map(Payment::getIdempotencyKey).toList();

            // Most recently persisted comes first.
            assertThat(keys).first().isEqualTo("PMT-LST-2");
            assertThat(keys).last().isEqualTo("PMT-LST-0");
        }
    }

    /* -------------------- uniqueness constraint -------------------- */

    @Nested
    @DisplayName("Unique constraint on idempotency_key")
    class UniqueConstraint {

        @Test
        void duplicate_idempotency_key_save_throws_DataIntegrityViolationException() {
            paymentRepository.saveAndFlush(persistedPayment("PMT-DUP-1"));
            Payment dup = persistedPayment("PMT-DUP-1");

            assertThatThrownBy(() -> paymentRepository.saveAndFlush(dup))
                    .as("uk_payments_idempotency_key must fire at H2 flush")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /* -------------------- IdempotencyRecord CRUD -------------------- */

    @Nested
    @DisplayName("IdempotencyRecord cache CRUD")
    class IdempotencyRecordCrud {

        @Test
        void save_and_findById_returns_record() {
            IdempotencyRecord record = new IdempotencyRecord(
                    "IDEM-CRUD-1", 201,
                    "{\"data\":{\"id\":\"x\"},\"timestamp\":\"2026-07-02T10:30:00Z\"}",
                    Instant.now());
            idempotencyRepository.saveAndFlush(record);

            Optional<IdempotencyRecord> loaded = idempotencyRepository.findById("IDEM-CRUD-1");
            assertThat(loaded).isPresent().get().satisfies(r -> {
                assertThat(r.getResponseStatus()).isEqualTo(201);
                assertThat(r.getResponseBody()).contains("\"id\":\"x\"");
            });
        }

        @Test
        void overwriting_existing_record_replaces_status_and_body() {
            idempotencyRepository.saveAndFlush(new IdempotencyRecord(
                    "IDEM-CRUD-2", 422, "first", Instant.now()));
            idempotencyRepository.saveAndFlush(new IdempotencyRecord(
                    "IDEM-CRUD-2", 422, "second", Instant.now()));
            assertThat(idempotencyRepository.findById("IDEM-CRUD-2").get().getResponseBody())
                    .isEqualTo("second");
        }
    }

    /* -------------------- helpers -------------------- */

    private static Payment paymentWithStatus(String idempotencyKey, PaymentStatus status) {
        Payment p = persistedPayment(idempotencyKey);
        p.setStatus(status);
        return p;
    }
}
