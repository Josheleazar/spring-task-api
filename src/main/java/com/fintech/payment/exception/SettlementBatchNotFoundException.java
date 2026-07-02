package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Raised when a {@code GET /api/v1/settlements/{id}} or
 * {@code POST /api/v1/settlements/{id}/process} call references a batch id
 * that does not exist. Mapped to HTTP 404 NOT_FOUND via
 * {@link DomainException#getHttpStatus()}.
 *
 * <p>Phase 4 KISS: one settlement-side exception handles both GET-by-id and
 * POST-process paths. If Phase 5 wants audit-side enqueueing of "settlement
 * not found" events, this exception already exposes cause-message + id in
 * its constructor string.</p>
 */
public class SettlementBatchNotFoundException extends DomainException {

    public SettlementBatchNotFoundException(UUID id) {
        super("SETTLEMENT_BATCH_NOT_FOUND",
                "SettlementBatch with id %s was not found".formatted(id));
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.NOT_FOUND;
    }
}
