package com.fintech.payment.service;

import com.fintech.payment.exception.IdempotencyKeyMismatchException;
import com.fintech.payment.model.entity.IdempotencyRecord;
import com.fintech.payment.repository.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Phase 3 idempotency cache layer + Phase 6 body-hash validation
 * (SRS §1.3 / §3.4 / FR-2.2).
 *
 * <p>Two coordinated methods:</p>
 * <ul>
 *   <li>{@link #lookup(String, byte[])} runs in the caller's transaction
 *       (default read-only). Returns the cached response — empty on
 *       miss or on body-hash mismatch (the mismatch path raises
 *       {@link IdempotencyKeyMismatchException} via
 *       {@link #lookupStrict}).</li>
 *   <li>{@link #save(String, int, String, byte[])} uses
 *       {@link Propagation#REQUIRES_NEW} so the cache write happens
 *       <em>outside</em> the controller's transaction. This guarantees
 *       that a 422 INSUFFICIENT_FUNDS (which would rollback the
 *       controller's tx) is still cached, so a faithful replay returns
 *       the same 422 envelope verbatim. Phase 6 also persists the SHA-256
 *       body hash so subsequent replays can detect body mismatches.</li>
 * </ul>
 *
 * <h2>Phase 6 §12.6.1 item (5): body-hash mismatch detection</h2>
 *
 * <p>Each cached record carries a {@code body_hash} column (SHA-256 hex
 * digest of the original request bytes). On a cache-hit, the filter
 * hashes the new request's bytes and compares. A mismatch raises
 * {@link IdempotencyKeyMismatchException} (422), preventing silent
 * replay against an unrelated payload that happens to share the
 * idempotency key.</p>
 *
 * <p>Backward-compat: rows persisted pre-Phase 6 have a {@code null}
 * {@code bodyHash}. A cache hit on a {@code null}-hash row returns the
 * cached response without mismatch-checking (legacy cache survives
 * Phase 6 deploys; new writes always include the hash).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyRepository repository;

    /**
     * Best-effort cache lookup that tolerates a {@code null} body. Returns
     * empty on miss <em>or</em> on body-mismatch (the strict variant
     * throws). Use {@link #lookupStrict} when replay semantics demand
     * explicit failure on mismatch.
     */
    @Transactional(readOnly = true)
    public Optional<CachedResponse> lookup(String idempotencyKey, byte[] requestBody) {
        return doLookup(idempotencyKey, requestBody, false);
    }

    /**
     * Strict cache lookup — raises {@link IdempotencyKeyMismatchException}
     * on body-hash mismatch. Used by {@code IdempotencyFilter} when a
     * cache row exists and the caller wants the mismatch surfaced as
     * a 422 rather than silently re-treating the row as a miss.
     */
    @Transactional(readOnly = true)
    public Optional<CachedResponse> lookupStrict(String idempotencyKey, byte[] requestBody) {
        return doLookup(idempotencyKey, requestBody, true);
    }

    private Optional<CachedResponse> doLookup(String idempotencyKey, byte[] requestBody, boolean strictMismatch) {
        Optional<IdempotencyRecord> opt = repository.findById(idempotencyKey);
        if (opt.isEmpty()) return Optional.empty();
        IdempotencyRecord record = opt.get();

        // Legacy-cached rows (pre-Phase-6) have null bodyHash. Honour the
        // legacy contract — replay as-is, no mismatch check.
        if (record.getBodyHash() == null) {
            log.debug("Idempotency cache HIT (legacy row, no bodyHash) for key={}", idempotencyKey);
            return Optional.of(new CachedResponse(record.getResponseStatus(), record.getResponseBody()));
        }

        String actualHash = sha256Hex(requestBody);
        if (!record.getBodyHash().equals(actualHash)) {
            log.warn("Idempotency-Key body-hash mismatch for key={} expected={} got={}",
                    idempotencyKey, record.getBodyHash(), actualHash);
            if (strictMismatch) {
                throw new IdempotencyKeyMismatchException(idempotencyKey, record.getBodyHash(), actualHash);
            }
            return Optional.empty();
        }
        return Optional.of(new CachedResponse(record.getResponseStatus(), record.getResponseBody()));
    }

    /**
     * Persist a response in a brand-new transaction independent of the
     * controller's. Failure here is logged but never propagates — caches
     * are best-effort. The {@code requestBody} bytes are hashed via
     * SHA-256 and stored alongside the response — Phase 6 §12.6.1 item
     * (5).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(String idempotencyKey, int responseStatus, String responseBody, byte[] requestBody) {
        try {
            String bodyHash = requestBody == null ? null : sha256Hex(requestBody);
            repository.save(new IdempotencyRecord(
                    idempotencyKey, responseStatus, responseBody, bodyHash, Instant.now()));
            log.debug("Cached response under idempotencyKey={} status={} ({} bytes, hash={})",
                    idempotencyKey, responseStatus,
                    responseBody == null ? 0 : responseBody.length(), bodyHash);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist idempotency cache for key={}: {}", idempotencyKey, ex.toString());
        }
    }

    /**
     * SHA-256 hex digest — used both at write time (record the body hash)
     * and at read time (compare incoming request body to cached hash).
     */
    public static String sha256Hex(byte[] body) {
        if (body == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a JDK-mandated algorithm — failure here means a
            // broken JVM. Re-throw as IllegalStateException to surface at boot.
            throw new IllegalStateException("SHA-256 not available on this JVM", ex);
        }
    }

    /**
     * Minimal projection — exactly what the {@code IdempotencyFilter} writes
     * back to {@code HttpServletResponse} on a cache hit.
     */
    public record CachedResponse(int status, String body) {
    }
}
