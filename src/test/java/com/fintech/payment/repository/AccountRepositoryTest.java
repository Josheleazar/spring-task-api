package com.fintech.payment.repository;

import com.fintech.payment.config.AuditingConfig;
import com.fintech.payment.model.entity.Account;
import com.fintech.payment.model.enums.AccountStatus;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository slice test for {@link AccountRepository} covering SRS FR-1.x
 * persistence semantics:
 * <ul>
 *   <li>round-trip save + findById with audit field population</li>
 *   <li>{@code existsByAccountNumber} and {@code findByAccountNumber}</li>
 *   <li>paginated {@code findAll} (size + sort behaviour)</li>
 *   <li>unique-constraint enforcement on duplicate account numbers</li>
 *   <li>{@code @Version} increment on update</li>
 * </ul>
 *
 * <p>{@code @DataJpaTest} brings up the JPA slice with an embedded H2 (H2 is
 * {@code runtime}-scoped on the parent pom and therefore visible to the test
 * classpath). {@code @Import(AuditingConfig.class)} wires {@code @EnableJpaAuditing}
 * so {@code @CreatedDate} / {@code @LastModifiedDate} are populated without
 * pulling in the rest of the production component scan.</p>
 */
@DataJpaTest
@Import(AuditingConfig.class)
@DisplayName("AccountRepository")
class AccountRepositoryTest {

    @Autowired
    private AccountRepository repository;

    /* -------------------- helpers -------------------- */

    private static Account newAccount(String accountNumber,
                                      String holder,
                                      BigDecimal balance,
                                      AccountStatus status) {
        Account account = Account.create(accountNumber, holder, balance, "USD");
        account.setStatus(status);
        return account;
    }

    /* -------------------- save + findById + audit -------------------- */

    @Nested
    @DisplayName("save + findById")
    class SaveAndFindById {

        @Test
        void save_persists_and_findById_returns_managed_entity_with_audit_fields_populated() {
            Account saved = repository.saveAndFlush(
                    newAccount("ACC-S-1", "Alice Anderson",
                            new BigDecimal("100.00"), AccountStatus.ACTIVE));

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();

            Optional<Account> reloaded = repository.findById(saved.getId());
            assertThat(reloaded)
                    .isPresent()
                    .get()
                    .satisfies(a -> {
                        assertThat(a.getAccountNumber()).isEqualTo("ACC-S-1");
                        assertThat(a.getAccountHolder()).isEqualTo("Alice Anderson");
                        assertThat(a.getBalance()).isEqualByComparingTo("100.00");
                        assertThat(a.getCurrency()).isEqualTo("USD");
                        assertThat(a.getStatus()).isEqualTo(AccountStatus.ACTIVE);
                        assertThat(a.getCreatedAt()).isEqualTo(saved.getCreatedAt());
                    });
        }

        @Test
        void updating_an_existing_entity_increments_version_and_bumps_updatedAt() {
            Account created = repository.saveAndFlush(
                    newAccount("ACC-V-1", "Bob Brown",
                            BigDecimal.ZERO, AccountStatus.ACTIVE));
            Long versionBefore = created.getVersion();
            var createdAtSnapshot = created.getUpdatedAt();

            created.setStatus(AccountStatus.FROZEN);
            Account updated = repository.saveAndFlush(created);

            assertThat(updated.getVersion())
                    .as("@Version must increment on every update")
                    .isGreaterThan(versionBefore);
            assertThat(updated.getUpdatedAt())
                    .as("@LastModifiedDate must move forward on update")
                    .isAfterOrEqualTo(createdAtSnapshot);
        }
    }

    /* -------------------- existsByAccountNumber (FR-1.1 uniqueness gate) -------------------- */

    @Nested
    @DisplayName("existsByAccountNumber")
    class ExistsByAccountNumber {

        @Test
        void returns_true_after_save() {
            repository.saveAndFlush(
                    newAccount("ACC-EXISTS-T", "Holder X",
                            BigDecimal.ZERO, AccountStatus.ACTIVE));
            assertThat(repository.existsByAccountNumber("ACC-EXISTS-T")).isTrue();
        }

        @Test
        void returns_false_for_unknown() {
            assertThat(repository.existsByAccountNumber("ACC-NEVER-WAS")).isFalse();
        }
    }

    /* -------------------- findByAccountNumber -------------------- */

    @Nested
    @DisplayName("findByAccountNumber")
    class FindByAccountNumber {

        @Test
        void returns_entity_when_present() {
            repository.saveAndFlush(
                    newAccount("ACC-FIND-T", "Holder Y",
                            new BigDecimal("1.00"), AccountStatus.ACTIVE));
            Optional<Account> found = repository.findByAccountNumber("ACC-FIND-T");

            assertThat(found)
                    .isPresent()
                    .get()
                    .satisfies(a -> assertThat(a.getAccountHolder()).isEqualTo("Holder Y"));
        }

        @Test
        void returns_empty_when_absent() {
            assertThat(repository.findByAccountNumber("ACC-NONE")).isEmpty();
        }
    }

    /* -------------------- findAll (paginated — FR-1.3) -------------------- */

    @Nested
    @DisplayName("findAll pageable (FR-1.3)")
    class FindAllPageable {

        @Test
        void honours_size_and_reports_totalElements() {
            for (int i = 0; i < 7; i++) {
                repository.saveAndFlush(newAccount(
                        "ACC-P-" + i, "Holder-" + i,
                        new BigDecimal(i + ".00"), AccountStatus.ACTIVE));
            }

            Page<Account> page0 = repository.findAll(PageRequest.of(0, 5));

            assertThat(page0.getContent()).hasSize(5);
            assertThat(page0.getTotalElements()).isEqualTo(7);
            assertThat(page0.getTotalPages()).isEqualTo(2);
            assertThat(page0.hasNext()).isTrue();
            assertThat(page0.hasPrevious()).isFalse();
        }

        @Test
        void honours_sort_by_createdAt_desc() {
            for (int i = 0; i < 3; i++) {
                repository.saveAndFlush(newAccount(
                        "ACC-S-" + i, "Holder-" + i,
                        new BigDecimal(i + ".00"), AccountStatus.ACTIVE));
            }

            Pageable sorted = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
            List<String> accountNumbers = repository.findAll(sorted)
                    .getContent().stream()
                    .map(Account::getAccountNumber)
                    .toList();

            // Most recently persisted comes first.
            assertThat(accountNumbers).first().isEqualTo("ACC-S-2");
            assertThat(accountNumbers).last().isEqualTo("ACC-S-0");
        }

        @Test
        void empty_page_reports_zero_totalElements() {
            Page<Account> empty = repository.findAll(PageRequest.of(0, 5));
            assertThat(empty.getContent()).isEmpty();
            assertThat(empty.getTotalElements()).isZero();
            assertThat(empty.getTotalPages()).isZero();
        }
    }

    /* -------------------- Unique-constraint enforcement (TOCTOU backstop) -------------------- */

    @Nested
    @DisplayName("Unique constraint")
    class UniqueConstraint {

        @Test
        void duplicate_account_number_save_throws_DataIntegrityViolationException() {
            repository.saveAndFlush(newAccount(
                    "ACC-DUP-T", "first instance",
                    BigDecimal.ZERO, AccountStatus.ACTIVE));
            Account duplicate = newAccount(
                    "ACC-DUP-T", "second instance",
                    BigDecimal.ZERO, AccountStatus.ACTIVE);

            assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                    .as("H2 must enforce uk_accounts_account_number at flush time")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
