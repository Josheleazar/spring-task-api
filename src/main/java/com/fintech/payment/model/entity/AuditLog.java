package com.fintech.payment.model.entity;

import com.fintech.payment.model.enums.AuditAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * AuditLog entity — SRS §3.5 (Phase 6 immutability-hardening).
 *
 * <h2>Immutability invariant (Phase 6 §12.6.1)</h2>
 *
 * <p>The append-only invariant (Phase-1 §12.2, FR-4.1) is now
 * <em>machine-checkable</em>, not merely conventional:</p>
 * <ul>
 *   <li>No {@code @Setter} — production code cannot mutate fields after
 *       construction (the canonical FR-4.1 enforcement).</li>
 *   <li>No {@code @AllArgsConstructor} — prevents Lombok from synthesising
 *       a public constructor that would allow re-creation with mutated
 *       fields.</li>
 *   <li>{@code @NoArgsConstructor(access = AccessLevel.PROTECTED)} — JPA
 *       requires a no-args constructor for proxy hydration (Hibernate
 *       field-access default holds because {@code @Id} is on the field).
 *       Visibility is {@code PROTECTED} so application code cannot call
 *       it; only Hibernate's reflection-based instantiator can.</li>
 *   <li>Targeted public constructor for the 6 mutable columns — single
 *       canonical construction path, easy to audit.</li>
 * </ul>
 *
 * <p>After a row is persisted, the entity cannot be mutated through any
 * application-level API. Hibernate's managed-flush lifecycle may write to
 * the {@code @Version}-less row (intentionally; we use field-access
 * defaults, no dirty-checking triggers on managed instances since
 * writes go through {@code save()} exclusively).</p>
 *
 * <h2>Indexing</h2>
 * <ul>
 *   <li>{@code ix_audit_logs_entity} on {@code (entity_type, entity_id)} —
 *       primary FR-4.2 query path.</li>
 *   <li>{@code ix_audit_logs_created_at_desc} on {@code created_at DESC} —
 *       chronological list and reconciliation window queries.</li>
 * </ul>
 *
 * <p>Why no JPA {@code @ManyToOne}: a single Payment reversal can emit
 * a multi-second storm of {@code @Audited} write attempts today; loading
 * the parent entity for every audit row would inflate Hibernate's first-
 * level cache. The {@code entityId} is a plain UUID column.</p>
 */
@Entity
@Table(name = "audit_logs",
        indexes = {
                @Index(name = "ix_audit_logs_entity",
                        columnList = "entity_type, entity_id"),
                @Index(name = "ix_audit_logs_created_at_desc",
                        columnList = "created_at DESC")
        })
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "entity_type", nullable = false, length = 32, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32, updatable = false)
    private AuditAction action;

    /**
     * Previous state as a JSON string. Nullable on CREATED (no prior
     * state); populated on STATUS_CHANGE / REVERSED via the Phase-6
     * SpEL wiring on {@link com.fintech.payment.audit.Audited}. Stored
     * as TEXT so arbitrary shapes are allowed.
     */
    @Column(name = "old_value", columnDefinition = "TEXT", updatable = false)
    private String oldValue;

    /**
     * New state as a JSON string. Source: Phase-6 SpEL
     * {@code newValueSpel} expression on {@code @Audited} (defaults to
     * empty, which audits writes null per AuditAspect KISS).
     */
    @Column(name = "new_value", columnDefinition = "TEXT", updatable = false)
    private String newValue;

    /**
     * Actor identifier. Phase 5 ships {@code "system"} as a constant
     * because no auth layer exists; Phase 8 production-roadmap §10.2 calls
     * out OAuth2/JWT as deferred to production. The column is wide enough
     * (128 chars) to hold a future JWT subject claim.
     */
    @Column(name = "performed_by", nullable = false, length = 128, updatable = false)
    private String performedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Targeted constructor — the single application-level construction
     * path. {@code id} and {@code createdAt} are populated by Hibernate
     * (UUID generation strategy + {@code @CreatedDate} auditing listener).
     */
    public AuditLog(String entityType,
                    UUID entityId,
                    AuditAction action,
                    String oldValue,
                    String newValue,
                    String performedBy) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.performedBy = performedBy;
    }
}
