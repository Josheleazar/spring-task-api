package com.fintech.payment.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fintech.payment.model.entity.AuditLog;
import com.fintech.payment.model.enums.AuditAction;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 5 audit-trail response DTO — mirrors {@link AuditLog} for FR-4.2.
 *
 * <p>{@code @JsonInclude(NON_NULL)} keeps the JSON envelope minimal when
 * {@code oldValue} or {@code newValue} is absent (e.g., on CREATED rows
 * where there is no prior state).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogResponse(
        UUID id,
        String entityType,
        UUID entityId,
        AuditAction action,
        String oldValue,
        String newValue,
        String performedBy,
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getEntityType(),
                log.getEntityId(),
                log.getAction(),
                log.getOldValue(),
                log.getNewValue(),
                log.getPerformedBy(),
                log.getCreatedAt()
        );
    }
}
