package com.fintech.payment.audit;

import com.fintech.payment.model.enums.AuditAction;
import com.fintech.payment.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;

/**
 * AuditAspect — Phase 5 cross-cutting audit interceptor.
 *
 * <h2>Why {@code @Around} (not {@code @AfterReturning})</h2>
 *
 * <p>{@code @AfterReturning} fires when the host method's body returns
 * successfully — but BEFORE the {@code @Transactional} boundary commits.
 * If the transaction subsequently rolls back (e.g., a downstream listener
 * throws, or a Phase 6+ AOP successor raises an exception during commit),
 * the audit row would already have been inserted under the rolled-back
 * transaction context and would disappear alongside the rolled-back
 * business state.</p>
 *
 * <p>The "phantom audit" defect is real. Fix:</p>
 * <ol>
 *   <li>Skip the {@code @AfterReturning} / {@code @After} patterns.</li>
 *   <li>Wrap the host method with {@code @Around} and capture the
 *       {@code ProceedingJoinPoint} arguments from the pre-proceed
 *       snapshot (so entityId can be extracted).</li>
 *   <li>Run {@code pjp.proceed()} and only register the audit write
 *       AFTER the body returns successfully, via
 *       {@link TransactionSynchronizationManager#registerSynchronization}.
 *       The synchronization runs {@code afterCommit}, which fires ONLY if
 *       the transaction actually commits — never on rollback.</li>
 * </ol>
 *
 * <p>If no ambient transaction is active (a {@code @Audited} method
 * invoked outside any {@code @Transactional} context), the audit row is
 * written directly via {@link AuditService#record} so the FR-4.1
 * invariant still holds for read-only paths.</p>
 *
 * <h2>Cross-bean isolation (Phase-2 §12.2.1 / Phase-4 §12.4.1 lesson)</h2>
 *
 * <p>This aspect is a separate {@code @Component} from the services it
 * audits. Spring AOP works through proxies; the proxy intercepts only
 * cross-bean calls. An {@code @Audited} method invoked via
 * {@code this.annotatedMethod(...)} within its own host class will
 * bypass this aspect silently. Document in the {@link Audited}
 * annotation.</p>
 *
 * <h2>Argument resolution</h2>
 *
 * <p>The host method's identifier argument is named via
 * {@link Audited#entityIdArg()} (default {@code "id"}). The aspect uses
 * Spring's {@link DefaultParameterNameDiscoverer} to map the name to
 * a parameter ordinal — this works on Maven-compiled bytecode when the
 * {@code -parameters} compiler flag is on, which is the Spring Boot
 * Maven default.</p>
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditAspect {

    private final AuditService auditService;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * {@code @Around} advice intercepting every method annotated with
     * {@link Audited}. Returns the host method's result unchanged.
     */
    @Around("@annotation(com.fintech.payment.audit.Audited) && @annotation(audited)")
    public Object auditAnnotatedMethod(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        // Snap the entityId BEFORE proceed(): the entityId is an input
        // argument, so it's stable across the call. Snapshots taken here
        // survive any mutation the host method performs on its arg.
        UUID entityId = resolveEntityId(pjp, audited.entityIdArg());

        Object result = pjp.proceed();
        // Only schedule the audit on a successful proceed() return.
        // An exception from proceed() falls through to the catch —
        // no audit row is written (no phantom on rollback).

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Ambient tx: defer the audit write to afterCommit. If the
            // tx rolls back, afterCommit never fires — the audit row
            // never inserts (defends FR-4.1 immutability invariant).
            final UUID finalEntityId = entityId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeAudit(audited, finalEntityId);
                }
            });
        } else {
            // No ambient tx: write directly. RARE path — Phase-5 surface
            // has no @Audited method invoked outside @Transactional today.
            writeAudit(audited, entityId);
        }
        return result;
    }

    private void writeAudit(Audited audited, UUID entityId) {
        try {
            UUID written = auditService.record(
                    audited.action(),
                    audited.entityType(),
                    entityId,
                    null,            // oldValue — Phase 5 KISS, always null
                    null,            // newValue — Phase 5 KISS, always null
                    audited.performedBy());
            log.debug("Audit row id={} entity={} entityId={} action={} performedBy={}",
                    written, audited.entityType(), entityId,
                    audited.action(), audited.performedBy());
        } catch (Exception ex) {
            // Audit writes must NEVER propagate — failures here would
            // roll back an already-committed business transaction or
            // surface a 500 to the client. Log at WARN so monitoring
            // can detect silent audit-loss.
            log.warn("Audit write failed for entity={} entityId={} action={}: {}",
                    audited.entityType(), entityId, audited.action(), ex.toString());
        }
    }

    /**
     * Resolves the host method's identifier argument by name. Falls back
     * to an empty UUID (logs warning) if the named parameter doesn't
     * exist — keeping the call site's behaviour auditable but flagging
     * the configuration gap in test logs.
     */
    private UUID resolveEntityId(ProceedingJoinPoint pjp, String name) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Parameter[] params = method.getParameters();
        String[] names = parameterNameDiscoverer.getParameterNames(method);

        if (names == null) {
            log.warn("Parameter names not discoverable on {}; audit row will carry null entityId. " +
                    "Did Maven compile with -parameters?", method.getName());
            return null;
        }

        for (int i = 0; i < names.length && i < params.length; i++) {
            if (name.equals(names[i])) {
                Object arg = pjp.getArgs()[i];
                if (arg instanceof UUID uuid) return uuid;
                if (arg != null) {
                    log.warn("Audit entityIdArg='{}' on {} resolved to non-UUID type {}; " +
                            "audit row will carry null entityId.",
                            name, method.getName(), arg.getClass().getSimpleName());
                }
                return null;
            }
        }
        log.warn("@Audited entityIdArg='{}' not found among {} parameters of {}; " +
                "audit row will carry null entityId.", name, names.length, method.getName());
        return null;
    }
}
