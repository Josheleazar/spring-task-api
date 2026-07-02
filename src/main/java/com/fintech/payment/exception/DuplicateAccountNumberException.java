package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised by {@link com.fintech.payment.service.AccountService#createAccount}
 * when the requested account number is already in use (FR-1.1 uniqueness,
 * enforced both at DB and service level). Mapped to HTTP 409.
 */
public class DuplicateAccountNumberException extends DomainException {

    public DuplicateAccountNumberException(String accountNumber) {
        super("DUPLICATE_ACCOUNT_NUMBER",
                "Account number '%s' is already in use".formatted(accountNumber));
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.CONFLICT;
    }
}
