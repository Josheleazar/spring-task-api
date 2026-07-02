package com.fintech.payment.controller;

import com.fintech.payment.exception.ValidationException;
import com.fintech.payment.model.dto.response.AuditLogResponse;
import com.fintech.payment.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Audit query endpoint — Phase 5 / FR-4.1 / FR-4.2.
 *
 * <p>Endpoint shape (per SRS §5.4):</p>
 * <pre>
 *   GET /api/v1/audit?entityType=PAYMENT&entityId={id}&page=0&size=50
 * </pre>
 *
 * <p>Response contracts:</p>
 * <ul>
 *   <li>{@code entityType} required (no filter defaults — guards against
 *       accidental full-table scans).</li>
 *   <li>{@code entityId} optional — when supplied, returns the trail for
 *       that single row (newest-first); when absent, returns a paginated
 *       {@code Page<AuditLogResponse>} of all rows for the entityType.</li>
 *   <li>Missing or malformed {@code entityId} returns 400
 *       {@code VALIDATION_FAILED} / {@code TYPE_MISMATCH} via the global
 *       exception handler.</li>
 * </ul>
 *
 * <h2>Permission scope</h2>
 *
 * <p>Phase 5 has no auth (Phase-8 production roadmap §10.2 calls out
 * OAuth2/JWT deferral). The endpoint is intentionally unrestricted. A
 * production hardening pass should add @PreAuthorize denials or gate
 * behind an OAuth2 resource-server config.</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
@Slf4j
public class AuditController {

    /** Tag asserted on every entityType query — guards against accidental full-table scans. */
    private static final java.util.Set<String> ALLOWED_ENTITY_TYPES =
            java.util.Set.of("ACCOUNT", "PAYMENT", "SETTLEMENT_BATCH");

    private final AuditService auditService;

    /**
     * Audit query endpoint.
     *
     * @param entityType "ACCOUNT" | "PAYMENT" | "SETTLEMENT_BATCH" — required.
     * @param entityId optional UUID — when supplied, returns the trail for that row.
     * @param pageable optional Spring Data {@code Pageable} — only used when {@code entityId} is null.
     * @return either a {@code List<AuditLogResponse>} (when entityId is supplied) or
     *         a {@code Page<AuditLogResponse>} (when entityId is omitted).
     */
    @GetMapping
    public Object queryAudit(
            @RequestParam("entityType") String entityType,
            @RequestParam(value = "entityId", required = false) UUID entityId,
            Pageable pageable) {

        if (entityType == null || entityType.isBlank()) {
            throw new ValidationException("entityType", "is required");
        }
        if (!ALLOWED_ENTITY_TYPES.contains(entityType)) {
            throw new ValidationException("entityType",
                    "must be one of " + ALLOWED_ENTITY_TYPES + " (got '" + entityType + "')");
        }

        if (entityId != null) {
            List<AuditLogResponse> trail = auditService.findByEntity(entityType, entityId);
            log.debug("Audit query entityType={} entityId={}: {} rows",
                    entityType, entityId, trail.size());
            return trail;
        }
        Page<AuditLogResponse> page = auditService.findByEntityType(entityType, pageable);
        log.debug("Audit query entityType={} (no entityId): page size={}/{}",
                entityType, page.getNumberOfElements(), page.getTotalElements());
        return page;
    }
}
