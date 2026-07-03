package com.fintech.payment.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Phase 7 §12.7.1 — dev-profile {@link BankAccountClient} mock.
 *
 * <p>Implements the {@code link(accountNumber)} seam per FR-6.5 with
 * configurable link-failure mode (default returns {@link
 * BankAccountLinkResult#LINKED}). State kept in-memory: a
 * {@link ConcurrentHashMap} of {@code accountNumber →
 * link-timestamp} so {@link BankAccountLinkResult#ALREADY_LINKED}
 * returns are deterministic when an account is re-linked.</p>
 *
 * <p>The {@link #requireReauthNext()} hook lets the integration test
 * toggle {@link BankAccountLinkResult#LINK_REQUIRES_REAUTH} on the
 * next invocation — surfaces the 409-CONFLICT path the SRS
 * requires.</p>
 *
 * <p>This mock does NOT synthesise account-holder metadata; Phase-7
 * KISS keeps the seam minimal. A real {@code RealPlaidClient}
 * returns a {@code BankAccountHolder} POJO with name + routing +
 * last-4 — surfaced as a forward-flag for Phase 8.</p>
 */
@Component
@Profile("dev")
@Slf4j
public class MockPlaidClient implements BankAccountClient {

    /** Synthetic holder-name returned in metadata; Phase-7 KISS keeps it minimal. */
    private static final String SYNTHETIC_HOLDER = "Synthetic Holder";

    private final ConcurrentMap<String, Instant> linked = new ConcurrentHashMap<>();

    /** Force the next link attempt for the given accountNumber to return REQUIRES_REAUTH. */
    private volatile String forceReauthNext = null;

    @Override
    public BankAccountLinkResult link(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            log.warn("MockPlaidClient rejected blank accountNumber");
            return BankAccountLinkResult.LINK_REQUIRES_REAUTH;
        }
        if (forceReauthNext != null && forceReauthNext.equals(accountNumber)) {
            forceReauthNext = null;
            log.warn("MockPlaidClient synthetic REQUIRES_REAUTH for accountNumber={}", accountNumber);
            return BankAccountLinkResult.LINK_REQUIRES_REAUTH;
        }
        Instant prior = linked.putIfAbsent(accountNumber, Instant.now());
        if (prior != null) {
            log.info("MockPlaidClient ALREADY_LINKED accountNumber={} (since {})", accountNumber, prior);
            return BankAccountLinkResult.ALREADY_LINKED;
        }
        log.info("MockPlaidClient LINKED accountNumber={} syntheticHolder='{}'", accountNumber, SYNTHETIC_HOLDER);
        return BankAccountLinkResult.LINKED;
    }

    /** Set the synthetic REQUIRES_REAUTH trap on the next call for {@code accountNumber}. */
    public void requireReauthNext(String accountNumber) {
        this.forceReauthNext = accountNumber;
        log.info("MockPlaidClient queued REQUIRES_REAUTH for next accountNumber={}", accountNumber);
    }

    /** Test helper — count of currently-linked accounts. */
    public int linkedCount() {
        return linked.size();
    }
}
