package com.fintech.payment.controller;

import com.fintech.payment.model.entity.Account;
import com.fintech.payment.model.entity.IdempotencyRecord;
import com.fintech.payment.model.enums.AccountStatus;
import com.fintech.payment.repository.AccountRepository;
import com.fintech.payment.repository.AuditLogRepository;
import com.fintech.payment.repository.IdempotencyRepository;
import com.fintech.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 §12.6.2 — controller-layer TOCTOU race integration test.
 *
 * <p>The Phase-6 {@link com.fintech.payment.service.IdempotencyServiceTest}
 * covered the cache layer's own TOCTOU invariant (16-thread save-key race
 * converges to exactly one cached row). This test covers the controller
 * layer of the same race — the actual production hot path: real HTTP
 * POSTs through Tomcat, the {@link
 * com.fintech.payment.idempotency.IdempotencyFilter}'s cache lookup /
 * cache replay logic, the {@link PaymentService#submitPayment} path, and
 * the DB-level {@code payments.idempotency_key} {@code UNIQUE} constraint
 * as the absolute backstop.</p>
 *
 * <h2>The race</h2>
 *
 * <p>N threads HTTP-POST the same {@code POST /api/v1/payments} payload
 * with the same {@code Idempotency-Key} header, fired off a {@link
 * CountDownLatch} so all requests hit Tomcat within the same
 * scheduler tick. Each thread runs the full production stack:</p>
 *
 * <ol>
 *   <li>{@link
 *       com.fintech.payment.idempotency.IdempotencyFilter#doFilterInternal}
 *       — cache lookup. If a different response was already cached
 *       before this thread entered, the cached 201 is replayed
 *       verbatim (winner-side replay). If not, the request is
 *       forwarded to the controller.</li>
 *   <li>{@link PaymentController#submit} → {@link
 *       PaymentService#submitPayment} — atomic debit + credit +
 *       {@code paymentRepository.save(...)} of a Payment row keyed by
 *       the {@code Idempotency-Key}.</li>
 *   <li>The DB unique constraint on {@code payments.idempotency_key}
 *       is the deterministic backstop: a second Payment row with
 *       the same key raises
 *       {@code DataIntegrityViolationException} → mapped to
 *       {@code 409 IDEMPOTENCY_KEY_CONFLICT} by the
 *       GlobalExceptionHandler.</li>
 *   <li>If the controller call committed (winner-path), the filter
 *       caches the 2xx response body so any subsequent same-key
 *       thread sees a cache hit and replays it.</li>
 * </ol>
 *
 * <h2>Invariants we assert</h2>
 *
 * <ul>
 *   <li><strong>Exactly one Payment row</strong> — the DB unique
 *       constraint guarantees no double-debit even if the cache layer
 *       is fully bypassed.</li>
 *   <li><strong>Exactly one money movement</strong> — source debited
 *       exactly {@code amount}, target credited exactly {@code amount},
 *       regardless of how many HTTP responses we got.</li>
 *   <li><strong>All N responses are either 201 (winner or replay) or
 *       409 (DB-unique loser) — NO 5xx / NO 422 / NO other
 *       envelopes.</strong> A 5xx would mean the race produced an
 *       unexpected exception (e.g., a 500 from the filter layer); a
 *       422 / 400 would mean the validation pipeline rejected the
 *       request differently between threads.</li>
 *   <li><strong>At least one winner (201)</strong> — proves the
 *       controller actually committed a payment.</li>
 *   <li><strong>Identical 201 bodies (modulo per-request timestamps)
 *       prove cache replay determinism</strong> — the cache replay
 *       path returns the same {@code PaymentResponse} body that the
 *       winner originally produced, not a fresh DB read.</li>
 * </ul>
 *
 * <h2>Hermeticity notes</h2>
 *
 * <ul>
 *   <li>{@code @SpringBootTest} with {@code RANDOM_PORT} brings up
 *       the FULL production context so the real
 *       IdempotencyFilter, AuditAspect, and Mock*Client wiring all
 *       participate. Settlement worker's async pool + daily-batch
 *       scheduler are explicitly disabled via the property source
 *       so the IT doesn't fire spurious background tasks.</li>
 *   <li>{@code @Transactional(propagation = NOT_SUPPORTED)} on the
 *       test class prevents the test runner from wrapping each test
 *       method in a rolled-back transaction — the
 *       {@code IdempotencyService.save} path uses
 *       {@code @Transactional(REQUIRES_NEW)} which would otherwise
 *       be ignored under a test-level tx, defeating the test surface.</li>
 *   <li>{@link AfterEach} explicitly deletes Payment / Account /
 *       AuditLog / IdempotencyRecord rows so each test run starts
 *       clean. {@code REQUIRES_NEW}-committed rows are NOT cleaned
 *       up by test-level rollback (none here, but defensively).</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Disable the Phase-4 @Scheduled daily-batch creation. The IT boots
        // the full context, so we don't want a spurious 0 0 0 * * * tick
        // materialising SettlementBatch rows or firing the
        // settlementWorkerExecutor mid-test.
        "spring.scheduling.enabled=false",
        // Same idea for the worker's async pool. The TOCTOU test only
        // exercises PaymentService.submitPayment (sync), never the
        // SettlementWorker retry-chain — keep the pool sized at 0
        // so any rogue @Async route is rejected cleanly rather than
        // queued.
        "spring.task.execution.pool.core-size=0",
        "spring.task.execution.pool.max-size=0"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("IdempotencyToctouIT — controller-layer §12.6.2 race")
class IdempotencyToctouIT {

    @LocalServerPort
    int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @AfterEach
    void cleanup() {
        // Order matters: Payments FK an Account via the source/target ids,
        // so the Payment rows must be deleted first or the FK constraint
        // trips. IdempotencyRecord and AuditLog rows have no FK to
        // Payment, but we delete them to keep count assertions clean.
        paymentRepository.deleteAll();
        idempotencyRepository.deleteAll();
        auditLogRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    @DisplayName("N concurrent POSTs with same Idempotency-Key → 1 payment row + balance moves once")
    void concurrent_posts_with_same_idempotency_key_yield_one_actual_payment()
            throws Exception {

        // 1. Seed two ACTIVE accounts (source funded, target empty). The
        //    Account entity uses a generated UUID primary key, so we save
        //    first and read back the IDs. The account_number column is
        //    VARCHAR(32) — keep the labels SHORT (<= 12 chars) to stay
        //    under that bound; the {@code @AfterEach} cleanup wipes
        //    them so a fixed label is safe across re-runs.
        Account sourceSeed = newAccount("TOCTOU-SRC", "USD", new BigDecimal("1000.00"));
        Account targetSeed = newAccount("TOCTOU-TGT", "USD", new BigDecimal("0.00"));
        Account source = accountRepository.save(sourceSeed);
        Account target = accountRepository.save(targetSeed);

        String idemKey = "TOCTOU-" + UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        // 2. Build the request body ONCE so all threads fire the same JSON.
        String body = """
                {"sourceAccountId":"%s","targetAccountId":"%s","amount":%s,"currency":"USD"}
                """.formatted(source.getId(), target.getId(), amount.toPlainString());

        // 3. Fire N concurrent requests, coordinated by a single
        //    CountDownLatch so all threads launch within the same
        //    scheduler window. N=16 matches the symmetry of the
        //    cache-layer IdempotencyServiceTest$ConcurrentReplay —
        //    bugs that only surface at 16+ threads are easier to miss
        //    with a lower count, and the IT-runtime cost is well under
        //    the 30s CountDownLatch budget.
        int threadCount = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        List<ResponseEntity<String>> responses =
                java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures =
                java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        HttpHeaders headers = new HttpHeaders();
                        headers.set(PaymentController.IDEMPOTENCY_KEY_HEADER, idemKey);
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        HttpEntity<String> request = new HttpEntity<>(body, headers);

                        long t0 = System.nanoTime();
                        ResponseEntity<String> response = restTemplate.exchange(
                                "http://localhost:" + port + "/api/v1/payments",
                                HttpMethod.POST, request, String.class);
                        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                        responses.add(response);
                        // Visibility for CI — surfaced in surefire stdout
                        // so a flaky-test investigation can read the
                        // response distribution directly without re-running.
                        System.out.printf("TOCTOU thread=%s status=%d elapsedMs=%d%n",
                                Thread.currentThread().getName(),
                                response.getStatusCode().value(),
                                elapsedMs);
                    } catch (InterruptedException iex) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                }));
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("all %d TOCTOU threads completed within 30s", threadCount)
                    .isTrue();
            for (Future<?> f : futures) f.get();

            // 4. INVARIANT 0: no thread crashed silently with an
            //    unchecked exception outside the HTTP layer. (The HTTP
            //    layer's 4xx/5xx envelopes are NOT counted as failures
            //    here — those are expected and asserted below.)
            assertThat(failures)
                    .as("no TOCTOU worker thread threw an unexpected exception")
                    .isEmpty();

            // 5. INVARIANT 1: every response is either 201 Created (winner
            //    OR a cache-replay) or 409 Conflict (DB-unique loser). No
            //    other status code is acceptable — a 500 would mean the
            //    filter / cache layer threw unexpectedly; a 422 / 400
            //    would mean the validation pipeline incorrectly rejected
            //    one of the duplicate calls.
            assertThat(responses).hasSize(threadCount);
            long winners = responses.stream()
                    .filter(r -> r.getStatusCode().value() == 201).count();
            long conflicts = responses.stream()
                    .filter(r -> r.getStatusCode().value() == 409).count();
            assertThat(winners + conflicts)
                    .as("every response is 201 or 409 IDEMPOTENCY_KEY_CONFLICT")
                    .isEqualTo(threadCount);
            assertThat(winners)
                    .as("at least one thread won the race and got a 201 back")
                    .isGreaterThanOrEqualTo(1L);

            // 6. INVARIANT 2 (DB-level, the real TOCTOU defence): exactly
            //    ONE Payment row exists. The DB unique constraint is the
            //    absolute backstop; the cache replay is a soft
            //    optimisation on top.
            long paymentCount = paymentRepository.count();
            assertThat(paymentCount)
                    .as("DB invariant: exactly one Payment row for the shared Idempotency-Key")
                    .isEqualTo(1L);

            // 7. INVARIANT 3 (money movement): source debited exactly
            //    amount, target credited exactly amount. If either is
            //    off, double-debit or under-debit would have leaked.
            Account sourceAfter = accountRepository.findById(source.getId())
                    .orElseThrow(() -> new IllegalStateException("source missing"));
            Account targetAfter = accountRepository.findById(target.getId())
                    .orElseThrow(() -> new IllegalStateException("target missing"));
            assertThat(sourceAfter.getBalance())
                    .as("source debited exactly the requested amount (TOCTOU money-invariant)")
                    .isEqualByComparingTo(new BigDecimal("900.00"));
            assertThat(targetAfter.getBalance())
                    .as("target credited exactly the requested amount (TOCTOU money-invariant)")
                    .isEqualByComparingTo(new BigDecimal("100.00"));

            // 8. INVARIANT 4 (cache replay determinism): all 201 responses
            //    share the same payment id (they're all replays of the
            //    same winner save). A divergent id between 201s would
            //    mean the cache stored a per-request snapshot rather
            //    than the winner's committed snapshot.
            String winnerPaymentId = responses.stream()
                    .filter(r -> r.getStatusCode().value() == 201)
                    .map(r -> extractPaymentId(r.getBody()))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            assertThat(winnerPaymentId)
                    .as("at least one 201 response carries a payment id")
                    .isNotNull();
            long distinct201Ids = responses.stream()
                    .filter(r -> r.getStatusCode().value() == 201)
                    .map(r -> extractPaymentId(r.getBody()))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .count();
            assertThat(distinct201Ids)
                    .as("all %d 201 responses reference the SAME payment id (cache-replay determinism)",
                            winners)
                    .isEqualTo(1L);

            // 9. INVARIANT 5 (filter cache-write guard): exactly ONE
            //    IdempotencyRecord row in the cache table. The
            //    IdempotencyFilter only persists on status >= 200 &&
            //    status < 300 (winner-only), so even with N=16
            //    concurrent same-key threads the three thread-fate
            //    categories converge to count == 1:
            //      • winner threads commit the controller tx → filter
            //        sees 2xx → cache save() commits one row;
            //      • loser threads losing the DB-unique constraint get
            //        409 → filter skips the cache write (the
            //        `log.debug(... SKIP ...)` branch);
            //      • post-cache threads hit cache lookup → replay
            //        201 → no new save.
            //    A `count` other than 1 indicates the filter's
            //    winner-only cache-write guard is broken. The most
            //    common observable failure is `count == 0` from
            //    the `catch (RuntimeException)` silently dropping the
            //    winner's save (defeats replay determinism — every
            //    subsequent same-key call would re-enter the
            //    cache-miss path and contend for the DB unique
            //    constraint). Note: `count > 1` is structurally
            //    defended by the cache's own
            //    `idempotency_records.PK == idempotency_key`, so it
            //    shouldn't happen in practice — this assertion is
            //    defense-in-depth. This invariant extends the
            //    cache-layer IdempotencyServiceTest$ConcurrentReplay
            //    assertion through the FULL controller-layer chain
            //    (real HTTP → filter → controller → DB → filter
            //    cache-save loop), not just the cache service in
            //    isolation.
            assertThat(idempotencyRepository.count())
                    .as("IdempotencyFilter cache-write guard: exactly one IdempotencyRecord row (winner-only)")
                    .isEqualTo(1L);

            // 10. INVARIANT 6 (filter cache-row content): the cached
            //     row's stored `responseStatus` MUST be 201 — not a
            //     4xx loser. This is the §12.3.3 widening-defence
            //     line, asserting that even if a future regression
            //     re-introduced caching of 4xx envelopes, the
            //     `count == 1 ∧ responseStatus == 409` failure mode
            //     (winner only, but cached a loser instead) would be
            //     caught here. Pairs with INVARIANT 5: count alone
            //     doesn't prove the winner was cached — content
            //     does.
            IdempotencyRecord cachedRow = idempotencyRepository.findById(idemKey).orElse(null);
            assertThat(cachedRow)
                    .as("cached IdempotencyRecord row exists for the winner's key")
                    .isNotNull();
            assertThat(cachedRow.getResponseStatus())
                    .as("cached row's status is the winner's 2xx (201), not a 4xx loser — Phase-3 §12.3.3 widening defence")
                    .isEqualTo(201);
            assertThat(cachedRow.getResponseBody())
                    .as("cached row's body contains the winner's payment id (chain INVARIANT 4 → 6 cross-validation: HTTP-level id-equality → cache-level content-equality)")
                    .contains(winnerPaymentId);
            // NOTE: do not move this block above INVARIANT 4 — `winnerPaymentId`
            // is declared there. Reordering would cause a "variable
            // winnerPaymentId might not have been initialized" compile error
            // (Java's definite-assignment rules, JLS §16). Java top-to-bottom
            // execution is the only ordering guarantee.
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Cheap JSON-side-id extractor: pulls the {@code "id":"...
     * "} substring out of an envelope without needing Jackson on
     * the test thread.
     */
    private static String extractPaymentId(String body) {
        if (body == null) return null;
        int dataIdx = body.indexOf("\"data\":");
        if (dataIdx < 0) return null;
        int idIdx = body.indexOf("\"id\":", dataIdx);
        if (idIdx < 0) return null;
        int q1 = body.indexOf('"', idIdx + 5);
        if (q1 < 0) return null;
        int q2 = body.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return body.substring(q1 + 1, q2);
    }

    private static Account newAccount(String accountNumber, String currency, BigDecimal balance) {
        Account a = new Account();
        a.setAccountNumber(accountNumber);
        // account_holder is NOT NULL on the accounts schema (Account.create
        // constructor also requires it). Use a fixed short label so the
        // overlap with the seeding pattern in other ITs is bounded.
        a.setAccountHolder("TOCTOU Holder");
        a.setCurrency(currency);
        a.setBalance(balance);
        a.setStatus(AccountStatus.ACTIVE);
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        return a;
    }
}
