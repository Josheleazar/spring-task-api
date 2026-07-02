package com.fintech.payment.audit;

import com.fintech.payment.model.enums.AuditAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Phase 5 marker annotation — declares that the host {@code @Service}
 * method produces an {@code AuditLog} row on commit.
 *
 * <p>Intercepted by {@link AuditAspect}, which uses
 * {@code TransactionSynchronizationManager.registerSynchronization(...)}
 * to defer the actual {@code AuditLog} insert until the host method's
 * {@code @Transactional} commit boundary. This guarantees:</p>
 * <ul>
 *   <li>Successful calls always produce an audit row (the
 *       canonical FR-4.1 invariant).</li>
 *   <li>Rolled-back calls NEVER produce a phantom audit row (the
 *       "transaction-afterCommit" pattern defeats the
 *       pre-commit-and-rollback defect that {@code @AfterReturning} alone
 *       would emit).</li>
 * </ul>
 *
 * <p>Self-invocation note (Phase-2 §12.2.1 + Phase-4 §12.4.1 lesson):
 * Spring AOP only intercepts cross-bean method calls. An {@code @Audited}
 * method invoked via {@code this.annotatedMethod(...)} from another
 * method in the same class bypasses {@code AuditAspect} entirely. The
 * aspect's responsibilities end at the proxy boundary; callers must
 * respect this constraint.</p>
 *
 * <h2>Argument extraction</h2>
 *
 * <p>The aspect needs three pieces of state per audited call:</p>
 * <ol>
 *   <li>{@link #entityType()} — the {@code audit_logs.entity_type} column
 *       value (e.g. {@code "PAYMENT"}, {@code "ACCOUNT"},
 *       {@code "SETTLEMENT_BATCH"}).</li>
 *   <li>{@link #entityIdArg()} — the name of the method argument that
 *       carries the {@code entityId} for the audit row. The aspect
 *       resolves it via Spring's parameter-name discovery; falls back
 *       to a compile-time-known parameter ordinal if names cannot be
 *       resolved (parameter discovery requires the {@code -parameters}
 *       compiler flag — both Spring Boot Maven defaults include it).</li>
 *   <li>{@link #action()} — the {@link AuditAction} enum value.</li>
 * </ol>
 *
 * <p>{@code oldValueJson} / {@code newValueJson} are intentionally NOT
 * declared on the annotation: Phase-5 KISS leaves both columns nullable
 * and writes them as {@code null}. A future hardening pass could add
 * SpEL expressions here ({@code "#result.status"} on a return value,
 * for example) without breaking today's call sites.</p>
 *
 * <p>{@code performedBy} defaults to {@code "system"} because no auth
 * layer exists yet (Phase-8 production roadmap §10.2 — OAuth2/JWT
 * deferred to production). A {@code SecurityContextHolder.getContext()
 * .getAuthentication().getName()} bridge may replace the default once
 * Spring Security is wired.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /**
     * Entity type tag persisted to {@code audit_logs.entity_type}.
     * Convention: SCREAMING_SNAKE_CASE matching the audit-row readers
     * (e.g. {@code "PAYMENT"}, {@code "ACCOUNT"}, {@code "SETTLEMENT_BATCH"}).
     */
    String entityType();

    /**
     * Audit action — drives the {@code audit_logs.action} enum column.
     */
    AuditAction action();

    /**
     * Method-argument name carrying the entity's primary key.
     * Resolved by Spring's parameter-name discovery against the host
     * method's formal parameters.
     *
     * <p>Example:</p>
     * <pre>{@code
     * @Audited(entityType = "PAYMENT", action = REVERSED, entityIdArg = "id")
     * public PaymentResponse reversePayment(UUID id, String reason) { ... }
     * }</pre>
     *
     * <p>If empty, the aspect falls back to looking for a parameter named
     * {@code "id"}; if neither matches, the audit row is logged with a
     * {@code null} entityId (logged as a warning so a forgotten
     * annotation can be caught early in tests).</p>
     */
    String entityIdArg() default "id";

    /**
     * Actor identity. Defaults to {@code "system"}.
     */
    String performedBy() default "system";
}
