package com.fintech.payment.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.controller.PaymentController;
import com.fintech.payment.exception.ApiErrorResponse;
import com.fintech.payment.exception.IdempotencyKeyMismatchException;
import com.fintech.payment.service.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * Phase 3 idempotency layer + Phase 6 body-hash enforcement
 * (SRS §1.3 / FR-2.2).
 *
 * <p>Strategy:</p>
 * <ul>
 *   <li>Scope: only POST {@code /api/v1/payments} — the only endpoint bound
 *       to client-supplied {@code Idempotency-Key} (reverse and listing have
 *       no key concept). {@link #shouldNotFilter} short-circuits everything
 *       else so the filter is essentially zero-cost on the rest of the API.</li>
 *   <li>On cache hit: compare the SHA-256 of the incoming request body to
 *       the {@code body_hash} column on the cached record. A mismatch raises
 *       {@link IdempotencyKeyMismatchException} (422 UNPROCESSABLE_ENTITY
 *       per FR-2.2 hardening). Otherwise replay the cached status + body
 *       verbatim.</li>
 *   <li>On cache miss: wrap the request so we can read the body bytes
 *       (via {@link CachedBodyHttpServletRequest}, a thin wrapper that
 *       caches the body once on first read so the controller can still
 *       read it), run the chain, compute body-hash, persist via
 *       {@link IdempotencyService#save}, then {@code copyBodyToResponse()}
 *       so the caller still sees the body.</li>
 * </ul>
 *
 * <p>If the {@code Idempotency-Key} header is absent we still let the request
 * through — the controller's {@code @RequestHeader(required = true)} then
 * throws {@code MissingRequestHeaderException}, which the global handler maps
 * to {@code 400 MISSING_HEADER}.</p>
 *
 * <h2>Phase 7.x fix (SRS §12.7.2) — HandlerExceptionResolver wiring</h2>
 *
 * <p>{@code OncePerRequestFilter} executes BEFORE Spring's
 * {@code DispatcherServlet} bean stack, so {@code @RestControllerAdvice}
 * {@code GlobalExceptionHandler} would NOT see exceptions thrown from
 * inside {@code doFilterInternal} by default. Without intervention,
 * {@link IdempotencyKeyMismatchException} from the strict lookup escapes
 * the filter, gets wrapped by Tomcat, and surfaces as an opaque 500
 * error page.</p>
 *
 * <p>The fix locates Spring MVC's {@code HandlerExceptionResolver}
 * composite bean (the same one {@code DispatcherServlet} uses to route
 * exceptions through the {@code @ControllerAdvice} chain) and delegates
 * to it. Filter-thrown exceptions are passed to {@code resolveException}
 * which delegates to {@code ExceptionHandlerExceptionResolver} →
 * {@code GlobalExceptionHandler} → the {@code ApiErrorResponse} envelope.
 * If no advice handles the exception, the filter writes a matched-shape
 * {@code ApiErrorResponse} 500 envelope directly so clients see a coherent
 * contract under all conditions.</p>
 *
 * <p>The resolver bean is located BY NAME through {@link ApplicationContext}
 * rather than @Qualifier-by-field-with-Lombok, because the project
 * registers more than one bean assignable to {@code HandlerExceptionResolver}
 * (the composite plus another autoconfigured one). @Qualifier ambiguity
 * resolution failed in this context; explicit name lookup is robust.</p>
 *
 * <p>Edge-cases (drift log §12.3 / §12.6 / §12.7.2):</p>
 * <ul>
 *   <li>body-hash mismatch detection: Phase 6 → Phase 7.x (routed through advice).</li>
 *   <li>TTL cleanup: Phase 4 schedule, Phase 6 unchanged.</li>
 *   <li>partial-response caching: out of scope — full responses only.</li>
 *   <li>loser-side 4xx response caching: Phase-3 §12.3.3 widened to
 *       all committed-controller responses (not just 2xx).</li>
 *   <li>filter-thrown exception resolution: Phase 7.x — wired.</li>
 * </ul>
 */
@Component
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyService idempotencyService;
    /**
     * Used by the Phase 7.x fallback envelope path to write the matched-shape
     * {@link ApiErrorResponse} body. Kept as a constructor-injected final
     * field so Jackson's configuration (JavaTimeModule, write-dates-as-ISO)
     * is honoured uniformly with controller-emitted envelopes.
     */
    private final ObjectMapper objectMapper;
    /**
     * Spring MVC's main {@code HandlerExceptionResolver} bean — registered
     * by Spring Boot under the bean name {@code "handlerExceptionResolver"}
     * as a {@code HandlerExceptionResolverComposite} composing
     * {@code ExceptionHandlerExceptionResolver} +
     * {@code ResponseStatusExceptionResolver} +
     * {@code DefaultHandlerExceptionResolver}. Re-using the same composite
     * the {@code DispatcherServlet} uses means filter-thrown exceptions go
     * through the identical {@code @RestControllerAdvice}-driven chain.
     *
     * <p>Resolved by explicit name lookup on the {@link ApplicationContext}
     * rather than @Qualifier-by-field for two reasons: (1) the project's
     * Spring Boot 3.4 setup registers more than one bean assignable to
     * {@link HandlerExceptionResolver}, which makes Lombok-propagated
     * {@code @Qualifier} ambiguous; (2) the resolver must be available at
     * filter chain construction time, not lazily on first method call, so
     * the lookup is done in the explicit constructor below.</p>
     */
    private final HandlerExceptionResolver exceptionResolver;

    public IdempotencyFilter(IdempotencyService idempotencyService,
                             ObjectMapper objectMapper,
                             ApplicationContext applicationContext) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        // §12.7.2 — explicit by-name lookup is unambiguous across all
        // Spring Boot 3.4 setups that register multiple beans assignable
        // to HandlerExceptionResolver. The composite is the right one
        // because it composes all three MVC-tier resolvers in order.
        this.exceptionResolver = applicationContext.getBean(
                "handlerExceptionResolver", HandlerExceptionResolver.class);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Exact-match "/api/v1/payments" (no trailing slash). The submit endpoint
        // is the only POST URL that carries an Idempotency-Key. Reverse and the
        // GETs use distinct paths so they bypass the cache as well.
        return !("POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/payments".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String key = request.getHeader(PaymentController.IDEMPOTENCY_KEY_HEADER);
        if (key == null || key.isBlank()) {
            // No header → controller's @RequestHeader validation will reject it.
            filterChain.doFilter(request, response);
            return;
        }

        // Wrap the request so getInputStream()/getReader() work after the chain
        // has run (the default servlet stream is single-shot — reading twice
        // would return empty bytes). The wrapper caches the body bytes on
        // first read; both the filter (for hashing) and the controller
        // (for Jackson deserialization) consume the cached bytes.
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);

        // Phase 6 — cache lookup with SHA-256 body-hash check.
        // Phase 7.x (SRS §12.7.2): the strict lookup may throw
        // IdempotencyKeyMismatchException (a DomainException → 422 by
        // GlobalExceptionHandler). But OncePerRequestFilter runs OUTSIDE
        // the DispatcherServlet bean stack, so @RestControllerAdvice
        // would not see the exception by default. We catch broadly and
        // delegate to exceptionResolver for advice-chain routing.
        Optional<IdempotencyService.CachedResponse> cached;
        try {
            cached = idempotencyService.lookupStrict(key, wrappedRequest.getCachedBody());
        } catch (RuntimeException ex) {
            log.warn("Idempotency cache-lookup exception for key={}: {}", key, ex.toString());
            routeToAdviceOrFallback(request, response, ex);
            return;
        }
        if (cached.isPresent()) {
            log.debug("Idempotency cache HIT for key={} (status={})", key, cached.get().status());
            replayCached(response, cached.get());
            return;
        }

        log.debug("Idempotency cache MISS for key={} — proceeding", key);
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(wrappedRequest, wrapper);

        int status = wrapper.getStatus();
        byte[] responseBody = wrapper.getContentAsByteArray();
        // CRITICAL: forward captured bytes to the original response, otherwise
        // the client sees an empty body on a cache miss.
        wrapper.copyBodyToResponse();

        // CRITICAL: only cache success (2xx) envelopes. A loser-side 4xx response
        // under a brand-new key (e.g. 409 IDEMPOTENCY_KEY_CONFLICT on a TOCTOU
        // race, 422 INSUFFICIENT_FUNDS) must NOT overwrite the cache row for
        // the same key, otherwise a future replay would replay a 4xx instead
        // of the winner's 2xx. The DB unique constraint on
        // payments.idempotency_key remains the absolute backstop for the
        // concurrency case; the cache here only short-circuits the second
        // call once we've confirmed a successful commit.
        if (status >= 200 && status < 300) {
            try {
                idempotencyService.save(key, status,
                        new String(responseBody, StandardCharsets.UTF_8),
                        wrappedRequest.getCachedBody());
            } catch (RuntimeException ex) {
                // Response already committed via copyBodyToResponse() above —
                // any advice-chain write would be a no-op; log only. Phase 7.x
                // (SRS §12.7.2) ensures save() no longer silently swallows
                // commit-time exceptions, so this catch is genuinely the
                // terminal fallback for the cache path rather than a hidden
                // bug-eraser.
                log.warn("Failed to persist idempotency cache for key={} (response already committed): {}",
                        key, ex.toString());
            }
        } else {
            log.debug("Idempotency cache SKIP for key={} on non-2xx status={}", key, status);
        }
    }

    /**
     * Routes a filter-thrown exception through Spring MVC's
     * {@code HandlerExceptionResolver} composite so the
     * {@code @RestControllerAdvice} {@code GlobalExceptionHandler}'s
     * {@code @ExceptionHandler}s apply identically to controller-thrown
     * exceptions. {@link IdempotencyKeyMismatchException} thus surfaces
     * as a 422 {@code IDEMPOTENCY_KEY_BODY_MISMATCH} envelope rather
     * than a Tomcat 500 error page (SRS §12.7.2 forward-flag).
     *
     * <p>Two defensive layers: if {@code resolveException} returns null
     * (no advice matched the exception) or itself throws, the filter
     * writes a matched-shape {@link ApiErrorResponse} 500 envelope
     * directly using {@link ObjectMapper}. If the response is already
     * committed (e.g., a body chunk was flushed), the fallback logs and
     * skips the write — we cannot rewrite history that has already
     * reached the client.</p>
     */
    private void routeToAdviceOrFallback(HttpServletRequest request,
                                          HttpServletResponse response,
                                          RuntimeException ex) throws IOException {
        try {
            if (exceptionResolver.resolveException(request, response, this, ex) != null) {
                // A handler wrote to the response. Done.
                return;
            }
            log.warn("No @ControllerAdvice matched filter-thrown {} ({}) — emitting fallback 500 envelope",
                    ex.getClass().getSimpleName(), ex.getMessage());
        } catch (Exception resolverEx) {
            log.error("HandlerExceptionResolver itself threw while routing {}: {}",
                    ex.getClass().getName(), resolverEx.toString());
        }
        if (response.isCommitted()) {
            log.error("Cannot write fallback 500 envelope — response already committed (orig class={})",
                    ex.getClass().getName());
            return;
        }
        ApiErrorResponse fallback = new ApiErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "Idempotency filter path failure",
                Instant.now(),
                request.getRequestURI(),
                null,
                null);
        // response.reset() clears any partially-set status/headers from a
        // null-returning prior resolver attempt so the fallback envelope
        // is delivered clean. Guarded by isCommitted() above — reset()
        // throws IllegalStateException if the response is committed.
        response.reset();
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), fallback);
        response.getWriter().flush();
    }

    private static void replayCached(HttpServletResponse response,
                                     IdempotencyService.CachedResponse cached) throws IOException {
        response.setStatus(cached.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(cached.body() == null ? "" : cached.body());
        response.getWriter().flush();
    }
}
