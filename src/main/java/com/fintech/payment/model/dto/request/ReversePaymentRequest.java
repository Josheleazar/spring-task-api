package com.fintech.payment.model.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Optional request body for {@code POST /api/v1/payments/{id}/reverse}.
 *
 * <p>Reversal accepts no required fields. The optional {@code reason} echoes the
 * audit-trail pattern established by {@code UpdateAccountStatusRequest} — Phase 5
 * will persist it to {@code AuditLog} (FR-4.1) when the audit module lands.</p>
 */
public record ReversePaymentRequest(
        @Size(max = 256) String reason) {
}
