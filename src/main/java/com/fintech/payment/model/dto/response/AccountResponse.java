package com.fintech.payment.model.dto.response;

import com.fintech.payment.model.entity.Account;
import com.fintech.payment.model.enums.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound representation of an account. Lives in the response DTO tree rather
 * than reusing the JPA entity directly so we never accidentally serialise lazy
 * proxies or expose JPA-managed state to clients.
 */
public record AccountResponse(
        UUID id,
        String accountNumber,
        String accountHolder,
        BigDecimal balance,
        String currency,
        AccountStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountHolder(),
                account.getBalance(),
                account.getCurrency(),
                account.getStatus(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
