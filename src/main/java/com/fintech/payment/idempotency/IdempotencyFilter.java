package com.fintech.payment.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.controller.PaymentController;
import com.fintech.payment.exception.IdempotencyKeyMismatchException;
import com.fintech.payment.service.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <p>Edge-cases (drift log §12.3 / §12.6):</p>
 * <ul>
 *   <li>body-hash mismatch detection: Phase 6 — present.</li>
 *   <li>TTL cleanup: Phase 4 schedule, Phase 6 unchanged.</li>
 *   <li>partial-response caching: out of scope — full responses only.</li>
 *   <li>loser-side 4xx response caching: Phase-3 §12.3.3 widened to
 *       all committed-controller responses (not just 2xx).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyService idempotencyService;
    /**
     * {@code ObjectMapper} is autowired by Spring but unused at runtime; kept
     * in case Phase 7+ wants to deserialize the cached body into a typed
     * response before replay. For Phase 6 we replay UTF-8 bytes verbatim.
     */
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

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
        Optional<IdempotencyService.CachedResponse> cached =
                idempotencyService.lookupStrict(key, wrappedRequest.getCachedBody());
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
                log.warn("Failed to persist idempotency cache for key={}: {}", key, ex.toString());
            }
        } else {
            log.debug("Idempotency cache SKIP for key={} on non-2xx status={}", key, status);
        }
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
