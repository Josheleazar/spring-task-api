package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Raised by {@link com.fintech.payment.service.PaymentService} when the source
 * and target accounts are the same (FR-2.5). Distinct from validation failures
 * because the request itself is well-formed (both ids present) but the
 * resource-level constraint forbids it.
 *
 * <p>Mapped to HTTP 400. The 4xx is a defence-in-depth choice: a naïvely-strict
 * client could read it as "your input is wrong" when actually the constant id
 * they always send is the problem. Alternative REST stylings would use 422.
 * 400 keeps the envelope family consistent with validation-failure codes.</p>
 */
public class SelfTransferException extends DomainException {

    public SelfTransferException(UUID accountId) {
        super("SELF_TRANSFER",
                "Source and target accounts must differ (id=%s)".formatted(accountId));
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
