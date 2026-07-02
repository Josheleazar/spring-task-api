package com.fintech.payment.service;

import com.fintech.payment.model.entity.IdempotencyRecord;
import com.fintech.payment.repository.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Phase 3 idempotency cache layer (SRS §1.3 / §3.4 / FR-2.2).
 *
 * <p>Two coordinated methods:</p>
 * <ul>
 *   <li>{@link #lookup(String)} runs in the caller's transaction (default
 *       read-only). Returns the cached response — empty on miss.</li>
 *   <li>{@link #save(String, int, String)} uses {@link Propagation#REQUIRES_NEW}
 *       so the cache write happens <em>outside</em> the controller's transaction.
 *       This guarantees that a 422 INSUFFICIENT_FUNDS (which would rollback
 *       the controller's tx) is still cached, so a faithful replay returns
 *       the same 422 envelope verbatim.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyRepository repository;

    /**
     * Read-only cache lookup. Returns empty on miss.
     */
    @Transactional(readOnly = true)
    public Optional<CachedResponse> lookup(String idempotencyKey) {
        return repository.findById(idempotencyKey)
                .map(r -> new CachedResponse(r.getResponseStatus(), r.getResponseBody()));
    }

    /**
     * Persist a response in a brand-new transaction independent of the
     * controller's. Failure here is logged but never propagates — caches are
     * best-effort.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(String idempotencyKey, int responseStatus, String responseBody) {
        try {
            repository.save(new IdempotencyRecord(
                    idempotencyKey, responseStatus, responseBody, Instant.now()));
            log.debug("Cached response under idempotencyKey={} status={} ({} bytes)",
                    idempotencyKey, responseStatus, responseBody == null ? 0 : responseBody.length());
        } catch (RuntimeException ex) {
            log.warn("Failed to persist idempotency cache for key={}: {}", idempotencyKey, ex.toString());
        }
    }

    /**
     * Minimal projection — exactly what the {@code IdempotencyFilter} writes
     * back to {@code HttpServletResponse} on a cache hit.
     */
    public record CachedResponse(int status, String body) {
    }
}
