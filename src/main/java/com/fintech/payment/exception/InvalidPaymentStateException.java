package com.fintech.payment.exception;

/**
 * Raised by {@link com.fintech.payment.service.PaymentService#reversePayment}
 * when the target payment is not in {@code COMPLETED} state and therefore cannot
 * be reversed. Inherits the default 400 {@code BAD_REQUEST} from
 * {@link DomainException} — the request shape is fine but the resource state
 * forbids it.
 */
public class InvalidPaymentStateException extends DomainException {

    public InvalidPaymentStateException(Object currentState) {
        super("INVALID_PAYMENT_STATE",
                "Cannot reverse a payment in %s state; only COMPLETED is reversible"
                        .formatted(currentState));
    }
}
