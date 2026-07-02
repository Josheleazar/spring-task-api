package com.fintech.payment.repository;

import com.fintech.payment.model.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Account}.
 *
 * <p>Inherits standard CRUD + pagination + sorting from {@link JpaRepository}.
 * Adds two account-number-specific finders used during account creation
 * (uniqueness check) and account lookup-by-external-id (Phase 3+ may add).</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    boolean existsByAccountNumber(String accountNumber);

    Optional<Account> findByAccountNumber(String accountNumber);
}
