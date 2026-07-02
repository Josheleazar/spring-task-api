package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * Raised by {@link com.fintech.payment.service.AccountService#updateAccountStatus}
 * when the requested target is {@code CLOSED} but the account's balance is not
 * zero (FR-1.5). Mapped to HTTP 422 Unprocessable Entity — the request is
 * syntactically valid and the transition itself is allowed, but the resource's
 * current balance prevents the operation.
 */
public class AccountNotClosableException extends DomainException {

    public AccountNotClosableException(String accountNumber, BigDecimal balance) {
        super("ACCOUNT_NOT_CLOSABLE",
                "Account '%s' cannot be closed because it has non-zero balance: %s"
                        .formatted(accountNumber, balance));
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
