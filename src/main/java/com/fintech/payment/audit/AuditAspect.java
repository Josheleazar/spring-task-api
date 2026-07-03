package com.fintech.payment.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AuditAspect — Phase 5 cross-cutting audit interceptor + Phase 6 SpEL wiring.
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
 * <h2>Phase 6 SpEL wiring (CRITICAL pre-afterCommit evaluation)</h2>
 *
 * <p>{@link Audited#oldValueSpel()} and {@link Audited#newValueSpel()} are
 * evaluated & serialized to JSON-strings <strong>BEFORE</strong> the
 * {@code afterCommit} callback runs. The Hibernate session may be
 * closed or in mid-flush by the time afterCommit fires; evaluating SpEL
 * against managed entities or invoking Jackson serialization at that
 * point would raise lazy-init or stale-state exceptions. Capturing the
 * flat Strings resolves the values once, immediately after
 * {@code pjp.proceed()} returns, when the session is still open.</p>
 *
 * <p>Expression objects are cached in a {@code ConcurrentHashMap} keyed
 * by method (one parse per audited method, not one per call). On a
 * 40-RPS REVERSED endpoint this cuts SpEL parser overhead to zero per
 * invocation. Cache is unbounded by design — the audit surface is finite.</p>
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
    private final ObjectMapper objectMapper;

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ExpressionParser spelParser = new SpelExpressionParser();

    /**
     * Compiled-Expression cache, keyed by host {@code Method}. Parsing
     * SpEL is non-trivial (regex compilation, AST build) — caching
     * eliminates per-call overhead on hot paths.
     */
    private final ConcurrentHashMap<SpelSlotKey, Expression> spelCache = new ConcurrentHashMap<>();

    /**
     * Composite key for the SpEL cache — separates oldValue from newValue
     * expressions on the same method (rare, but cheap to encode).
     */
    private record SpelSlotKey(Method method, boolean isOldValue) {}

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
        // An exception from proceed() falls through to the catch — no
        // audit row is written (no phantom on rollback).

        // PHASE-6 SpEL evaluation + JSON serialization NOW (pre-afterCommit),
        // while the Hibernate session is still open. The captured String
        // snapshots are the only state carried into the afterCommit
        // synchronization — no SpEL evaluation, no Jackson serialization,
        // no Hibernate proxy lookup happens after afterCommit fires.
        String oldValueJson = resolveSpelValue(pjp, audited.oldValueSpel(), audited, result, true);
        String newValueJson = resolveSpelValue(pjp, audited.newValueSpel(), audited, result, false);
        final String finalizedOld = oldValueJson;
        final String finalizedNew = newValueJson;

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Ambient tx: defer the audit write to afterCommit.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeAudit(audited, entityId, finalizedOld, finalizedNew);
                }
            });
        } else {
            // No ambient tx: write directly. Tests frequently hit this
            // path (AuditLogRepositoryTest doesn't wrap in @Transactional).
            writeAudit(audited, entityId, finalizedOld, finalizedNew);
        }
        return result;
    }

    private void writeAudit(Audited audited, UUID entityId, String oldValue, String newValue) {
        try {
            UUID written = auditService.record(
                    audited.action(),
                    audited.entityType(),
                    entityId,
                    oldValue,
                    newValue,
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
     * to {@code null} (logs warning) if the named parameter doesn't exist
     * — keeping the call site's behaviour auditable but flagging the
     * configuration gap.
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

    /**
     * Evaluates one of the SpEL slots on the {@code @Audited} annotation,
     * serializes the result to JSON. Returns {@code null} on:
     * <ul>
     *   <li>empty / blank expression (intentional KISS default),</li>
     *   <li>SpEL parse error (defensive — must not propagate),</li>
     *   <li>SpEL evaluation error (defensive),</li>
     *   <li>Jackson serialization error (defensive).</li>
     * </ul>
     *
     * <p>The session-open window is critical: this whole method runs
     * synchronously inside {@link #auditAnnotatedMethod} between
     * {@code pjp.proceed()} and the {@code registerSynchronization} call.
     * Jackson reads proxy state via the live Hibernate session.</p>
     */
    private String resolveSpelValue(ProceedingJoinPoint pjp,
                                    String spelExpression,
                                    Audited audited,
                                    Object result,
                                    boolean isOldValueSlot) {
        if (spelExpression == null || spelExpression.isBlank()) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Method method = signature.getMethod();
            Expression compiled = spelCache.computeIfAbsent(
                    new SpelSlotKey(method, isOldValueSlot),
                    k -> spelParser.parseExpression(spelExpression));

            EvaluationContext ctx = buildEvaluationContext(pjp, method, result);
            Object value = compiled.getValue(ctx);
            if (value == null) {
                return null;
            }
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("SpEL serialization failed for entity={} action={} slot={}: {}",
                    audited.entityType(), audited.action(),
                    isOldValueSlot ? "oldValue" : "newValue", ex.toString());
            return null;
        } catch (RuntimeException ex) {
            log.warn("SpEL evaluation failed for entity={} action={} slot={}: {}",
                    audited.entityType(), audited.action(),
                    isOldValueSlot ? "oldValue" : "newValue", ex.toString());
            return null;
        }
    }

    /**
     * Builds the SpEL {@code EvaluationContext} for the host method:
     * named method-args + positional {@code #p0}, {@code #p1}, …
     * + {@code #result} for the return value.
     */
    private EvaluationContext buildEvaluationContext(ProceedingJoinPoint pjp, Method method, Object result) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        String[] names = parameterNameDiscoverer.getParameterNames(method);
        Object[] args = pjp.getArgs();
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                ctx.setVariable(names[i], args[i]);
                ctx.setVariable("p" + i, args[i]);
            }
        } else {
            for (int i = 0; i < args.length; i++) {
                ctx.setVariable("p" + i, args[i]);
            }
        }
        ctx.setVariable("result", result);
        // beanResolver intentionally left null — SpEL's default behaviour
        // for #someBean references is to throw SpelEvaluationException,
        // which our outer try/catch logs at WARN and converts to a null
        // column value. No need to plug a custom resolver.
        return ctx;
    }
}
