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
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * AuditLog entity — SRS §3.5.
 *
 * <p>Append-only invariant (Phase-1 §12.2, FR-4.1): no {@code @Version},
 * no {@code @LastModifiedDate}, no setter mutation API outside Spring Data
 * proxies. The entity deliberately carries a Lombok {@code @Setter} from
 * Phase-3-3 Lombok defaults for repository construction; <strong>production
 * code never calls a setter on an {@code AuditLog}</strong> after the row
 * is persisted (the audit-immutability NFR is enforced by convention).
 * A future hardening pass may drop {@code @Setter} on {@code createdAt}
 * to make this idiomatic; documented as a Phase-6 polish item.</p>
 *
 * <p>Indexing:</p>
 * <ul>
 *   <li>{@code ix_audit_logs_entity} on {@code (entity_type, entity_id)} —
 *       primary FR-4.2 query path;
 *       {@code repository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc}
 *       is the dominant read pattern.</li>
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
@Setter
@NoArgsConstructor
@AllArgsConstructor
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
     * state); populated on STATUS_CHANGE / REVERSED for the most common
     * observed transitions. Stored as TEXT so arbitrary shapes are allowed.
     * Phase 6 may enforce a serializer-side validation schema.
     */
    @Column(name = "old_value", columnDefinition = "TEXT", updatable = false)
    private String oldValue;

    /**
     * New state as a JSON string. Nullable only when an {@code @Audited}
     * method emits audit without a captured new state (Phase 5 ships no
     * such case; left nullable so future fields don't require a schema
     * change).
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
}
