package com.fintech.payment.model.dto.request;

import com.fintech.payment.model.enums.AccountStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/v1/accounts/{id}/status} (FR-1.4 / FR-1.5).
 *
 * <p>The {@code reason} field is captured here for audit-trail purposes; Phase 5
 * will persist it to {@code AuditLog}. The service layer stores it transiently
 * (logged) until the audit subsystem lands.</p>
 */
public record UpdateAccountStatusRequest(
        @NotNull AccountStatus status,
        @Size(max = 256) String reason) {
}
