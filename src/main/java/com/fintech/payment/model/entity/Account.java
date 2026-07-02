package com.fintech.payment.model.entity;

import com.fintech.payment.model.enums.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Account entity — SRS §3.1.
 *
 * <p>Persistence notes:</p>
 * <ul>
 *   <li>{@code @EntityListeners(AuditingEntityListener.class)} is required by Spring
 *       Data JPA Auditing for the {@code @CreatedDate} / {@code @LastModifiedDate}
 *       fields to be populated. {@code @EnableJpaAuditing} is wired in
 *       {@code config.AuditingConfig}.</li>
 *   <li>{@code @Version} enables optimistic locking — concurrent updates to the same
 *       row produce {@code OptimisticLockingFailureException}, which the global
 *       exception handler maps to HTTP 409 (FR-5.2).</li>
 *   <li>Status is stored as a {@code VARCHAR(16)} via {@code EnumType.STRING} so that
 *       adding a new state in future phases does not depend on ordinal position.</li>
 * </ul>
 */
@Entity
@Table(name = "accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_accounts_account_number", columnNames = "account_number"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, length = 32)
    private String accountNumber;

    @Column(name = "account_holder", nullable = false, length = 128)
    private String accountHolder;

    /**
     * Current balance with {@code NUMERIC(18,2)} precision. Future payment flow
     * (Phase 3) will mutate this with atomic debit / credit inside a
     * {@code @Transactional} boundary; the {@code @Version} field guards against
     * the lost-update problem.
     */
    @Column(name = "balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    /** ISO 4217 currency code (3 uppercase letters). See DTO validation for the regex. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Factory for a new {@code ACTIVE} account at the application layer. Centralises
     * the "new account starts ACTIVE with this balance" rule so it can be reasoned
     * about in one place.
     */
    public static Account create(String accountNumber,
                                 String accountHolder,
                                 BigDecimal initialBalance,
                                 String currency) {
        Account account = new Account();
        account.accountNumber = accountNumber;
        account.accountHolder = accountHolder;
        account.balance = initialBalance == null ? BigDecimal.ZERO : initialBalance;
        account.currency = currency;
        account.status = AccountStatus.ACTIVE;
        return account;
    }
}
