package com.fintech.payment.service;

import com.fintech.payment.model.dto.response.AuditLogResponse;
import com.fintech.payment.model.entity.AuditLog;
import com.fintech.payment.model.enums.AuditAction;
import com.fintech.payment.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Phase 5 audit-log writer + read API — backs {@link com.fintech.payment.controller.AuditController}
 * and {@link com.fintech.payment.audit.AuditAspect}.
 *
 * <h2>Write path</h2>
 *
 * <p>{@link #record} runs at {@link Propagation#REQUIRES_NEW}. The class-level
 * annotation ensures every audit-write sparks a fresh transaction. This is
 * deliberate: the audit row lives independently of the calling service's
 * transaction so that</p>
 * <ul>
 *   <li>successful write + successful commit → audit row persists;</li>
 *   <li>successful write + parent rollback → audit row does NOT persist
 *       (defended by the {@code AuditAspect} afterCommit pattern in
 *       <em>normal cases</em>; this belt-and-braces propagation prevents
 *       ambient-tx rollback from inadvertently rolling back an audit
 *       insert).</li>
 * </ul>
 *
 * <h2>Read path</h2>
 *
 * <p>{@link #findByEntity} / {@link #findAll} are exposed for the
 * {@code AuditController} — they use the default read-only transaction
 * inherited from the controller's bound context. No custom annotation;
 * the class-level {@link Propagation#REQUIRES_NEW} is on the write path
 * only (read methods inherit the Spring default {@link Propagation#REQUIRED}).</p>
 *
 * <p>Phase 5 intentionally does NOT expose a {@code count} or {@code search}
 * helper — the controller enforces the {@code entityType} filter for the
 * FR-4.2 endpoint (defended in Javadoc there).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    /** Phase 5 default actor — Phase 8 production-roadmap §10.2 deferred OAuth2/JWT. */
    public static final String DEFAULT_PERFORMED_BY = "system";

    private final AuditLogRepository auditLogRepository;

    /* -------------------- Write path (REQUIRES_NEW) -------------------- */

    /**
     * Inserts an AuditLog row in a fresh transaction. Returns the persisted
     * row's id so callers (the AuditAspect) can correlate via logs.
     *
     * <p>Both {@code oldValue} and {@code newValue} are nullable — pass
     * {@code null} for the canonical CREATED record (no prior state) and
     * for any future {@code @Audited} annotation that produces audit
     * rows without snapshotting entity state (Phase 5 KISS default).</p>
     *
     * <p>Phase-6 immutability: the constructor-only API on
     * {@link AuditLog} (no setters, no AllArgsConstructor) is exercised here.
     * Production code cannot mutate a constructed {@code AuditLog} after
     * this method returns — the FR-4.1 append-only invariant is enforced
     * by the type system, not by convention.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID record(AuditAction action,
                       String entityType,
                       UUID entityId,
                       String oldValue,
                       String newValue,
                       String performedBy) {
        AuditLog row = new AuditLog(
                entityType,
                entityId,
                action,
                oldValue,
                newValue,
                performedBy == null ? DEFAULT_PERFORMED_BY : performedBy);
        AuditLog saved = auditLogRepository.save(row);
        return saved.getId();
    }

    /* -------------------- Read path -------------------- */

    /**
     * Trail for a single {@code (entityType, entityId)} row, newest-first.
     * Direct mirror of the entity-name query path; intended for the FR-4.2
     * endpoint when {@code entityId} is supplied.
     */
    public List<AuditLogResponse> findByEntity(String entityType, UUID entityId) {
        return auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId)
                .stream()
                .map(AuditLogResponse::from)
                .toList();
    }

    /**
     * Page of audit rows for a given {@code entityType}, newest-first.
     * Used when {@code entityId} is absent on the FR-4.2 endpoint.
     */
    public Page<AuditLogResponse> findByEntityType(String entityType, Pageable pageable) {
        return auditLogRepository
                .findByEntityTypeOrderByCreatedAtDesc(entityType, pageable)
                .map(AuditLogResponse::from);
    }

    /**
     * Page over the full audit log, newest-first. Default when no filter
     * is supplied (still paged; full-table scan would otherwise fit in
     * O(min(pageSize, total))) which is fine for Phase-5 KISS).
     */
    public Page<AuditLogResponse> findAll(Pageable pageable) {
        return auditLogRepository.findAll(pageable).map(AuditLogResponse::from);
    }

    /**
     * Read-window query — backs {@code ReconciliationReportService}. Spring
     * Data derives the JPQL {@code WHERE created_at >= :from AND created_at < :to}
     * from the method name; index ix_audit_logs_created_at_desc (per
     * {@link AuditLog}) drives the order.
     */
    public List<AuditLog> findByWindow(java.time.Instant from, java.time.Instant to) {
        return auditLogRepository.findByWindow(from, to);
    }
}
