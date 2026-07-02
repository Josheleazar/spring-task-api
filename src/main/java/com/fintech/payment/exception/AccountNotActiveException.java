package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when the service layer attempts to debit, freeze, or otherwise mutate
 * an account that is not in the {@code ACTIVE} state (Phase 3 payment-side
 * enforcement of FR-1.4). Today this exception is defined for completeness but
 * not thrown by the account service itself — Phase 3's PaymentService will be
 * its primary caller.
 *
 * <p>Mapped to HTTP 409 Conflict. The client may retry with a different
 * account; the "retry guidance" pattern matches FR-5.2.</p>
 */
public class AccountNotActiveException extends DomainException {

    public AccountNotActiveException(String accountNumber, Object state) {
        super("ACCOUNT_NOT_ACTIVE",
                "Account '%s' is in %s state and cannot perform this operation"
                        .formatted(accountNumber, state));
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.CONFLICT;
    }
}
