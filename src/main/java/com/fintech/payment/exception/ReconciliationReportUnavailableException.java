package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * ReconciliationReportUnavailableException — Phase 5 / FR-4.3.
 *
 * <p>Thrown when {@code GET /api/v1/reports/daily?date=…} is called with a
 * date in the future. Reconciliation is computed from the audit-log
 * window; a future date has no events to summarise.</p>
 *
 * <p>Maps to HTTP 422 (request well-formed but resource state makes the
 * operation semantically meaningless). Matches the INSUFFICIENT_FUNDS /
 * CURRENCY_MISMATCH 422 envelope family used in Phase 3.</p>
 */
public class ReconciliationReportUnavailableException extends DomainException {

    public ReconciliationReportUnavailableException(String message) {
        super("RECONCILIATION_REPORT_UNAVAILABLE", message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
