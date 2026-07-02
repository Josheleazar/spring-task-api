package com.fintech.payment.exception;

/**
 * Raised by {@link com.fintech.payment.service.AccountService#updateAccountStatus}
 * when the requested status target is not reachable from the current status (e.g.
 * {@code CLOSED → ACTIVE}). Distinct from
 * {@link com.fintech.payment.exception.AccountNotClosableException} (which would
 * be raised for the same request shape in the {@code CLOSED} target case
 * <em>only if balance != 0</em>).
 *
 * <p>Inherits the default 400 {@code BAD_REQUEST} from {@link DomainException}.
 * The request is syntactically valid but the resource state forbids it.</p>
 */
public class InvalidAccountStatusTransitionException extends DomainException {

    public InvalidAccountStatusTransitionException(Object from, Object to) {
        super("INVALID_STATUS_TRANSITION",
                "Cannot transition account status from %s to %s".formatted(from, to));
    }
}
