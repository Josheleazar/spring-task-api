package com.fintech.payment.repository;

import com.fintech.payment.config.AuditingConfig;
import com.fintech.payment.model.entity.SettlementBatch;
import com.fintech.payment.model.enums.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Phase 4 repository test, mirroring the Phase-2/3 shape:
 * {@code @DataJpaTest} for JPA contract, {@code @Import(AuditingConfig.class)}
 * to wire {@code @EnableJpaAuditing} so {@code @CreatedDate},
 * {@code @LastModifiedDate}, {@code @Version} are exercised on
 * {@link SettlementBatch}.
 *
 * <p>The injected {@link Clock} bean (Phase-4 {@code TimeConfig}) is
 * stubbed to a fixed instant so test runs are deterministic and don't
 * depend on wall-clock at midnight boundary conditions.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase
@Import(AuditingConfig.class)
@DisplayName("SettlementRepository")
class SettlementRepositoryTest {

    @Autowired
    private SettlementRepository repository;

    @MockitoBean
    private Clock clock;

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 2);

    private SettlementBatch freshBatch(LocalDate date) {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchDate(date);
        batch.setStatus(SettlementStatus.OPEN);
        batch.setTotalPayments(0);
        batch.setTotalAmount(ZERO);
        batch.setCurrency("USD");
        return batch;
    }

    @Nested
    @DisplayName("save + findById")
    class SaveAndFindById {

        @Test
        void persists_with_audit_and_version_zero() {
            SettlementBatch saved = repository.saveAndFlush(freshBatch(TODAY));
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getVersion()).isEqualTo(0L);
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        void second_save_bumps_version() {
            SettlementBatch saved = repository.saveAndFlush(freshBatch(TODAY));
            saved.setStatus(SettlementStatus.PROCESSING);
            repository.saveAndFlush(saved);
            assertThat(saved.getVersion()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("findByBatchDate")
    class FindByBatchDate {

        @Test
        void returns_batch_when_present() {
            repository.saveAndFlush(freshBatch(TODAY));
            Optional<SettlementBatch> found = repository.findByBatchDate(TODAY);
            assertThat(found).isPresent();
            assertThat(found.get().getStatus()).isEqualTo(SettlementStatus.OPEN);
        }

        @Test
        void returns_empty_when_absent() {
            assertThat(repository.findByBatchDate(TODAY.plusDays(99))).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByStatus (paginated)")
    class FindByStatus {

        @Test
        void returns_only_matching_status() {
            repository.saveAndFlush(freshBatch(TODAY));
            SettlementBatch open2 = freshBatch(TODAY.plusDays(1));
            repository.saveAndFlush(open2);
            open2.setStatus(SettlementStatus.SETTLED);
            repository.saveAndFlush(open2);

            Page<SettlementBatch> openPage = repository.findByStatus(
                    SettlementStatus.OPEN,
                    PageRequest.of(0, 10));
            assertThat(openPage.getTotalElements()).isEqualTo(1);
            assertThat(openPage.getContent().get(0).getBatchDate()).isEqualTo(TODAY);

            Page<SettlementBatch> settledPage = repository.findByStatus(
                    SettlementStatus.SETTLED,
                    PageRequest.of(0, 10));
            assertThat(settledPage.getTotalElements()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("findAllPageable")
    class FindAllPageable {

        @Test
        void sorts_by_batch_date_desc() {
            repository.saveAndFlush(freshBatch(TODAY));
            repository.saveAndFlush(freshBatch(TODAY.minusDays(1)));
            repository.saveAndFlush(freshBatch(TODAY.minusDays(2)));

            Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "batchDate"));
            Page<SettlementBatch> page = repository.findAll(pageable);
            assertThat(page.getTotalElements()).isEqualTo(3);
            assertThat(page.getContent())
                    .extracting(SettlementBatch::getBatchDate)
                    .containsExactly(TODAY, TODAY.minusDays(1), TODAY.minusDays(2));
        }

        @Test
        void empty_page_when_no_rows() {
            Page<SettlementBatch> page = repository.findAll(PageRequest.of(0, 10));
            assertThat(page.getTotalElements()).isZero();
            assertThat(page.getContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("unique constraint on batch_date (FR-3.1 idempotency on daily tick)")
    class UniqueConstraintOnBatchDate {

        @Test
        void duplicate_batch_date_throws_data_integrity_violation() {
            repository.saveAndFlush(freshBatch(TODAY));
            assertThatThrownBy(() ->
                    repository.saveAndFlush(freshBatch(TODAY)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
