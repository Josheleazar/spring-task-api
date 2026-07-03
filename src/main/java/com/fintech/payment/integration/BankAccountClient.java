package com.fintech.payment.integration;

import com.fintech.payment.model.entity.Account;

/**
 * Phase 7 — bank-account-linking seam (SRS §2 / FR-6.5).
 *
 * <p>{@code BankAccountClient} is the application-side interface that
 * abstracts the external bank-account linking flow (Plaid / Teller
 * in production; {@link MockPlaidClient} in dev per FR-6.5).</p>
 *
 * <p>The mock returns {@link BankAccountLinkResult#LINKED} by default;
 * the configurable-failure mode lets ops toggle an {@code ALREADY_LINKED}
 * or {@code LINK_REQUIRES_REAUTH} path so the integration-test
 * envelope matrix has a meaningful surface.</p>
 *
 * <p>Phase-7 integration point: {@code AccountService.createAccount} (per
 * §12.7.1 verdict), before the JPA save. If the link returns anything
 * other than {@link BankAccountLinkResult#LINKED}, the service aborts
 * with {@link BankAccountLinkResult}-carrying exception — the
 * {@code DomainException.getHttpStatus()} override maps to 409 Conflict.</p>
 */
public interface BankAccountClient {

    /**
     * Link (or fetch) the bank-account record behind an
     * {@link Account#getAccountNumber()} value.
     *
     * @param accountNumber the human-readable account number (the field's
     *                      uniqueness-anchored identifier — see Phase 2
     *                      {@code AccountRepository.existsByAccountNumber}).
     * @return one of three link-result states
     *         ({@link BankAccountLinkResult#LINKED},
     *          {@link BankAccountLinkResult#ALREADY_LINKED},
     *          {@link BankAccountLinkResult#LINK_REQUIRES_REAUTH}).
     */
    BankAccountLinkResult link(String accountNumber);
}
