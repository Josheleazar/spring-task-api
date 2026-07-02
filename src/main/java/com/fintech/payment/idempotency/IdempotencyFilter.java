package com.fintech.payment.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.controller.PaymentController;
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
 * Phase 3 idempotency layer (SRS §1.3 / FR-2.2).
 *
 * <p>Strategy:</p>
 * <ul>
 *   <li>Scope: only POST {@code /api/v1/payments} — the only endpoint bound
 *       to client-supplied {@code Idempotency-Key} (reverse and listing have
 *       no key concept). {@link #shouldNotFilter} short-circuits everything
 *       else so the filter is essentially zero-cost on the rest of the API.</li>
 *   <li>On cache hit: write the cached status + body directly to
 *       {@link HttpServletResponse} and {@code return} — controller never
 *       executes.</li>
 *   <li>On cache miss: wrap the response in
 *       {@link ContentCachingResponseWrapper}, run the chain, extract bytes,
 *       persist via {@link IdempotencyService#save}, then
 *       {@code copyBodyToResponse()} so the caller still sees the body.</li>
 * </ul>
 *
 * <p>If the {@code Idempotency-Key} header is absent we still let the request
 * through — the controller's {@code @RequestHeader(required = true)} then
 * throws {@code MissingRequestHeaderException}, which the global handler maps
 * to {@code 400 MISSING_HEADER}.</p>
 *
 * <p>Edge-cases intentionally out of scope for Phase 3 (documented in §12.3
 * drift log): body-hash mismatch detection, TTL cleanup, partial-response
 * caching (only full responses are cached), loser-side 4xx response caching
 * (the cache write is 2xx-guarded post-§12.3.3 so a TOCTOU 409
 * IDEMPOTENCY_KEY_CONFLICT cannot overwrite the winner's cached 201 envelope).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyService idempotencyService;
    /**
     * {@code ObjectMapper} is autowired by Spring but unused at runtime; kept
     * in case Phase 6 wants to deserialize the cached body into a typed
     * response before replay. For Phase 3 we replay UTF-8 bytes verbatim.
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

        Optional<IdempotencyService.CachedResponse> cached = idempotencyService.lookup(key);
        if (cached.isPresent()) {
            log.debug("Idempotency cache HIT for key={} (status={})",
                    key, cached.get().status());
            replayCached(response, cached.get());
            return;
        }

        log.debug("Idempotency cache MISS for key={} — proceeding", key);
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapper);

        int status = wrapper.getStatus();
        byte[] body = wrapper.getContentAsByteArray();
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
                idempotencyService.save(key, status, new String(body, StandardCharsets.UTF_8));
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
