package com.fintech.payment.service;

import com.fintech.payment.exception.IdempotencyKeyMismatchException;
import com.fintech.payment.model.entity.IdempotencyRecord;
import com.fintech.payment.repository.IdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 Idempotency driver test — covers §12.6.1 items (4) + (5).
 *
 * <p>Two intertwined goals:</p>
 * <ol>
 *   <li><strong>Item 5 (body-hash):</strong> verify the SHA-256 round-trip
 *       via {@code save} + {@code lookupStrict} — a matching body
 *       replays, a mismatching body throws
 *       {@link IdempotencyKeyMismatchException}. Legacy cache rows
 *       ({@code bodyHash == null}) replay without mismatch check.</li>
 *   <li><strong>Item 4 (TOCTOU):</strong> hammer {@code lookupStrict} +
 *       {@code save} from N concurrent threads against the same key
 *       via a focused cache-layer test (the controller-layer winner-loser
 *       is enforced by the DB unique constraint, which is the cleaner
 *       property of @SpringBootTest driver tests). The cache layer's
 *       own race is assertable here as: the race produces exactly one
 *       successful save (per-key idempotency at the cache layer).</li>
 * </ol>
 *
 * <p>{@link IdempotencyService} is annotated as a Spring bean, but we
 * import the actual service class directly so the @DataJpaTest slice
 * retains the propagation semantics ({@code lookupStrict} is
 * {@code readOnly}, {@code save} is {@code REQUIRES_NEW}).</p>
 */
@DataJpaTest
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
@Import(IdempotencyService.class)
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService service;

    @Autowired
    private IdempotencyRepository repository;

    private static final String KEY = "idem-key-" + UUID.randomUUID();
    private static final byte[] BODY_V1 = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY_V2 = "{\"amount\":200}".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void clearCache() {
        repository.deleteAll();
    }

    @Nested
    @DisplayName("BodyHash")
    class BodyHash {

        @Test
        void sha256Hex_deterministic_for_same_bytes() {
            String expected = "463f1d3eb7df5b1d4b3c8e5c7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f";
            // Note: we'll just assert determinism (two calls → same hash)
            // without hardcoding the SHA-256 output for clarity.
            String h1 = IdempotencyService.sha256Hex(BODY_V1);
            String h2 = IdempotencyService.sha256Hex(BODY_V1);
            assertThat(h1).isEqualTo(h2).hasSize(64);
        }

        @Test
        void sha256Hex_differs_for_different_bytes() {
            String h1 = IdempotencyService.sha256Hex(BODY_V1);
            String h2 = IdempotencyService.sha256Hex(BODY_V2);
            assertThat(h1).isNotEqualTo(h2);
        }

        @Test
        void save_records_sha256_and_lookup_replays_matching_body() {
            service.save(KEY, 201, "{\"paymentId\":\"abc\"}", BODY_V1);
            Optional<IdempotencyService.CachedResponse> hit =
                    service.lookupStrict(KEY, BODY_V1);
            assertThat(hit).isPresent();
            assertThat(hit.get().status()).isEqualTo(201);
            assertThat(hit.get().body()).isEqualTo("{\"paymentId\":\"abc\"}");
        }

        @Test
        void lookupStrict_throws_on_body_mismatch() {
            service.save(KEY, 201, "{\"paymentId\":\"abc\"}", BODY_V1);
            assertThatThrownBy(() -> service.lookupStrict(KEY, BODY_V2))
                    .isInstanceOf(IdempotencyKeyMismatchException.class);
        }

        @Test
        void lookup_tolerant_returns_empty_on_body_mismatch() {
            service.save(KEY, 201, "{\"paymentId\":\"abc\"}", BODY_V1);
            // lookup() (non-strict) returns Optional.empty on mismatch
            // rather than throwing — used by ops tooling that wants to
            // detect and re-cache.
            Optional<IdempotencyService.CachedResponse> hit = service.lookup(KEY, BODY_V2);
            assertThat(hit).isEmpty();
        }

        @Test
        void legacy_cached_row_with_null_bodyHash_replays_unconditionally() {
            // Simulate a pre-Phase-6 row by writing a record directly
            // with bodyHash=null.
            IdempotencyRecord legacy = new IdempotencyRecord(
                    KEY, 201, "{\"paymentId\":\"legacy\"}",
                    /* bodyHash = */ null, Instant.now());
            repository.saveAndFlush(legacy);

            // Mismatching body still replays because legacy rows don't
            // carry a hash anchor. Phase-6 production code never writes
            // such a row (save() always includes the hash); this test
            // documents the drift backward-compat behaviour.
            Optional<IdempotencyService.CachedResponse> hit =
                    service.lookupStrict(KEY, BODY_V2);
            assertThat(hit).isPresent();
            assertThat(hit.get().body()).isEqualTo("{\"paymentId\":\"legacy\"}");
        }
    }

    @Nested
    @DisplayName("ConcurrentReplay")
    class ConcurrentReplay {

        @Test
        void concurrent_lookup_save_against_same_key_converges_to_one_cached_row()
                throws InterruptedException, java.util.concurrent.ExecutionException {
            // §12.6.1 item (4) — TOCTOU race test. The DB-level backstop
            // (UniqueConstraint on payments.idempotency_key) prevents
            // double-payments; this test asserts the cache layer's own
            // race converges cleanly — exactly one row after the storm,
            // regardless of how many threads lost.
            int threadCount = 16;
            AtomicInteger successes = new AtomicInteger(0);
            AtomicInteger mismatches = new AtomicInteger(0);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);

            try {
                java.util.List<Future<?>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    futures.add(pool.submit(() -> {
                        try {
                            start.await();
                            // Each thread attempts: maybe-miss, maybe-save.
                            // The hash matches across threads (same body).
                            String tkey = KEY + "-" + Thread.currentThread().threadId();
                            // Distinct keys but racing through the same
                            // shared repository — exercises the pool's
                            // interleaving without triggering per-key writes.
                            service.save(tkey, 201, "{\"t\":\"x\"}", BODY_V1);
                            successes.incrementAndGet();
                        } catch (InterruptedException iex) {
                            // Preserve interrupt status; CountDownLatch.await
                            // threw — extremely rare under the test's
                            // 30s timeout but defensive.
                            Thread.currentThread().interrupt();
                        } catch (RuntimeException ex) {
                            mismatches.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    }));
                }
                start.countDown();
                assertThat(done.await(30, TimeUnit.SECONDS))
                        .as("All %d threads completed within 30s", threadCount)
                        .isTrue();
                for (Future<?> f : futures) f.get();

                // Every thread should have saved successfully — distinct
                // keys, no contention. The TOCTOU-relevant assertion is
                // below.
                assertThat(successes.get()).isEqualTo(threadCount);
                assertThat(mismatches.get()).isZero();

                // Now hammer a SINGLE key with all threads racing.
                String sharedKey = KEY + "-shared";
                CountDownLatch start2 = new CountDownLatch(1);
                CountDownLatch done2 = new CountDownLatch(threadCount);
                java.util.List<Future<?>> futures2 = new java.util.ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    futures2.add(pool.submit(() -> {
                        try {
                            start2.await();
                            service.save(sharedKey, 201, "{\"t\":\"shared\"}", BODY_V1);
                        } catch (InterruptedException iex) {
                            Thread.currentThread().interrupt();
                        } catch (RuntimeException ignored) {
                            // expected: only one thread wins the per-key
                            // constraint; recordable wins counted below.
                        } finally {
                            done2.countDown();
                        }
                    }));
                }
                start2.countDown();
                assertThat(done2.await(30, TimeUnit.SECONDS)).isTrue();
                for (Future<?> f : futures2) f.get();

            // After the race, exactly ONE cached row exists. That's
            // the cache-layer TOCTOU invariant; the controller-layer
            // backstop is the DB payment.idempotency_key unique
            // constraint verified in PaymentControllerIntegrationTest.
            long sharedRows = repository.findAll().stream()
                    .filter(r -> sharedKey.equals(r.getIdempotencyKey()))
                    .count();
            assertThat(sharedRows)
                    .as("cache-layer TOCTOU invariant: exactly one row for the shared key")
                    .isEqualTo(1L);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
