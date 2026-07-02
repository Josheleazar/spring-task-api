package com.fintech.payment.repository;

import com.fintech.payment.model.entity.AuditLog;
import com.fintech.payment.model.enums.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AuditLog read API — Phase 5 query surface.
 *
 * <p>The composite {@code (entity_type, entity_id)} index from
 * {@link AuditLog} drives {@link #findByEntityTypeAndEntityIdOrderByCreatedAtDesc}.
 * The {@code created_at DESC} index drives the chronological list +
 * reconciliation window queries.</p>
 *
 * <p>The operations are read-only; the append-only contract is enforced
 * upstream by convention — Phase 5 ships no {@code deleteBy*} or
 * {@code update*By*} derived methods.</p>
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /* -------------------- FR-4.2: query by entity -------------------- */

    /**
     * Trail for a single (entityType, entityId) row, newest-first.
     * Primary FR-4.2 query path.
     */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, UUID entityId);

    Page<AuditLog> findByEntityTypeOrderByCreatedAtDesc(
            String entityType, Pageable pageable);

    long countByEntityTypeAndEntityId(String entityType, UUID entityId);

    /* -------------------- Reconciliation report (FR-4.3) -------------------- */

    /**
     * All audit events in the half-open window {@code [from, to)} — used
     * by {@code ReconciliationReportService} to derive the daily tally.
     */
    @Query("SELECT a FROM AuditLog a WHERE a.createdAt >= :from AND a.createdAt < :to ORDER BY a.createdAt ASC")
    List<AuditLog> findByWindow(@Param("from") Instant from, @Param("to") Instant to);

    /* -------------------- Reversed-lookup by action (audit forensics) -------------------- */

    List<AuditLog> findByActionOrderByCreatedAtDesc(AuditAction action, Pageable pageable);
}
