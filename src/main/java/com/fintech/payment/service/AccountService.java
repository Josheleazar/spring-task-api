package com.fintech.payment.service;

import com.fintech.payment.exception.AccountNotClosableException;
import com.fintech.payment.exception.DuplicateAccountNumberException;
import com.fintech.payment.exception.InvalidAccountStatusTransitionException;
import com.fintech.payment.exception.ResourceNotFoundException;
import com.fintech.payment.model.dto.request.CreateAccountRequest;
import com.fintech.payment.model.dto.response.AccountResponse;
import com.fintech.payment.model.entity.Account;
import com.fintech.payment.model.enums.AccountStatus;
import com.fintech.payment.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2 account service — covers FR-1.1 .. FR-1.5.
 *
 * <p>Transaction strategy:</p>
 * <ul>
 *   <li>Class-level {@code @Transactional(readOnly = true)} so default reads share a
 *       read-only transaction context, hinting Hibernate/JDBC to skip dirty-checking
 *       on writes.</li>
 *   <li>Mutating methods override with {@code @Transactional} (read-write),
 *       keeping the per-method boundary explicit.</li>
 * </ul>
 *
 * <p>Concurrency: Account entities carry a {@code @Version} column. Two concurrent
 * status transitions race on the same row, the second loses and Spring throws
 * {@code OptimisticLockingFailureException} → mapped to HTTP 409 by the global
 * exception handler (FR-5.2).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AccountService {

    /**
     * Allowed status transitions. {@code CLOSED} is terminal (no outgoing edges).
     * Enforced by {@link #updateAccountStatus} before any persistence.
     */
    private static final Map<AccountStatus, Set<AccountStatus>> ALLOWED_TRANSITIONS = Map.of(
            AccountStatus.ACTIVE, EnumSet.of(AccountStatus.FROZEN, AccountStatus.CLOSED),
            AccountStatus.FROZEN, EnumSet.of(AccountStatus.ACTIVE, AccountStatus.CLOSED),
            AccountStatus.CLOSED, EnumSet.noneOf(AccountStatus.class)
    );

    private final AccountRepository accountRepository;

    /* -------------------- FR-1.1: Create account -------------------- */

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        if (accountRepository.existsByAccountNumber(request.accountNumber())) {
            throw new DuplicateAccountNumberException(request.accountNumber());
        }
        BigDecimal initialBalance = request.initialBalance() == null
                ? BigDecimal.ZERO
                : request.initialBalance();

        Account account = Account.create(
                request.accountNumber(),
                request.accountHolder(),
                initialBalance,
                request.currency());
        Account saved = accountRepository.save(account);
        log.info("Created account {} ({}) holder='{}' currency={} balance={}",
                saved.getAccountNumber(), saved.getId(), saved.getAccountHolder(),
                saved.getCurrency(), saved.getBalance());
        return AccountResponse.from(saved);
    }

    /* -------------------- FR-1.2: Get account -------------------- */

    public AccountResponse getAccount(UUID id) {
        return accountRepository.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    /* -------------------- FR-1.3: List accounts (pageable) -------------------- */

    public Page<AccountResponse> listAccounts(Pageable pageable) {
        return accountRepository.findAll(pageable).map(AccountResponse::from);
    }

    /* -------------------- FR-1.4 / FR-1.5: Status transitions -------------------- */

    /**
     * Single entry-point for status transitions. Validates the request, validates the
     * source→target edge against {@link #ALLOWED_TRANSITIONS}, and (for
     * {@code → CLOSED}) the FR-1.5 zero-balance rule. The save round-trip lets the
     * {@code @Version} column perform optimistic-lock detection.
     */
    @Transactional
    public AccountResponse updateAccountStatus(UUID id, AccountStatus newStatus, String reason) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));

        AccountStatus currentStatus = account.getStatus();
        if (!ALLOWED_TRANSITIONS.get(currentStatus).contains(newStatus)) {
            throw new InvalidAccountStatusTransitionException(currentStatus, newStatus);
        }

        if (newStatus == AccountStatus.CLOSED
                && account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new AccountNotClosableException(account.getAccountNumber(), account.getBalance());
        }

        account.setStatus(newStatus);
        Account saved = accountRepository.save(account);
        log.info("Account {} status {} -> {} (reason='{}', version {} -> {})",
                saved.getAccountNumber(), currentStatus, newStatus, reason,
                account.getVersion(), saved.getVersion());
        return AccountResponse.from(saved);
    }

    /* -------------------- Convenience helpers --------------------
     *
     * Each helper is annotated {@code @Transactional} explicitly. Without it, the
     * class-level {@code @Transactional(readOnly = true)} would make Spring's AOP
     * proxy wrap these calls in a read-only transaction; the internal
     * {@code this.updateAccountStatus(...)} dispatch is a self-invocation that
     * bypasses Spring's proxy, so the inner method's own {@code @Transactional}
     * annotation never fires. The outer annotation promotes the whole delegating
     * call to a read-write transaction.
     */

    @Transactional
    public AccountResponse freezeAccount(UUID id, String reason) {
        return updateAccountStatus(id, AccountStatus.FROZEN, reason);
    }

    @Transactional
    public AccountResponse unfreezeAccount(UUID id, String reason) {
        return updateAccountStatus(id, AccountStatus.ACTIVE, reason);
    }

    @Transactional
    public AccountResponse closeAccount(UUID id, String reason) {
        return updateAccountStatus(id, AccountStatus.CLOSED, reason);
    }
}
